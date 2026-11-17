package de.firetail.compat.movebank.mcp.tools;

import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mcp.MirrorDirectory;
import de.firetail.compat.movebank.mirror.StudyJson;
import de.firetail.compat.movebank.mirror.reader.Event;
import de.firetail.compat.movebank.mirror.reader.EventColumns;
import de.firetail.compat.movebank.mirror.reader.EventQuery;
import de.firetail.compat.movebank.mirror.reader.EventVisitor;
import de.firetail.compat.movebank.mirror.reader.MirrorReader;
import de.firetail.compat.movebank.mirror.reader.MovebankEventCsv;
import de.firetail.compat.movebank.mirror.reader.SensorType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The 8 LLM-callable tools exposed by movebank-mcp.
 *
 * Design principles:
 *
 *   - Summary-by-default, files-on-request. Most Movebank data is too
 *     big for context. summarize_events first, download_events_to_file
 *     when you know what to ask for.
 *   - No conversational admin tools. No accept_license, no mirror_sync.
 *     Those live outside the MCP conversation; the LLM context is
 *     "exploring data that already exists in this mirror."
 *
 * The tools read the mirror directory in-process through movebank-mirror's
 * {@link MirrorReader}: the same reader movebank-mirror-api serves from, so
 * events come in Movebank's order with Movebank's deployment assignment.
 */
@Service
public class MovebankTools {

    private final MirrorDirectory mirror;

    public MovebankTools(MirrorDirectory mirror) {
        this.mirror = mirror;
    }

    // ── Metadata / discovery ──────────────────────────────────────────────

    @Tool(description = """
            List studies present in the mirror. Returns compact one-line
            summaries (id, name, taxon, n_individuals, year range, PI).
            Use this first to discover what's available before
            calling get_study or summarize_events.""")
    public Object list_studies(
            @ToolParam(required = false,
                       description = "Free-text, case-insensitive filter applied to " +
                                     "study name / taxon / PI. Omit to list all mirrored studies.")
            String filter
    ) {
        MirrorReader reader;
        try {
            reader = mirror.reader();
        } catch (IllegalStateException e) {
            return error(e);
        }
        List<Map<String, Object>> studies = new ArrayList<>();
        String f = (filter == null || filter.isBlank()) ? null : filter.toLowerCase();
        for (StudyJson sj : reader.studies()) {
            Map<String, String> row = sj.study == null ? Map.of() : sj.study;
            String name = col(row, "name");
            String taxon = col(row, "taxon_ids");
            String pi = col(row, "principal_investigator_name");
            if (f != null && !(name.toLowerCase().contains(f) ||
                               taxon.toLowerCase().contains(f) ||
                               pi.toLowerCase().contains(f))) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", parseLongSafe(col(row, "id")));
            s.put("name", name);
            s.put("taxon", taxon);
            s.put("n_individuals", parseIntSafe(col(row, "number_of_individuals")));
            s.put("deployment_year_range", yearRange(
                    col(row, "timestamp_first_deployed_location"),
                    col(row, "timestamp_last_deployed_location")));
            s.put("sensor_types", col(row, "sensor_type_ids"));
            s.put("pi", pi);
            studies.add(s);
        }
        return studies;
    }

    @Tool(description = """
            Full metadata for one study: name, objective, citation, license,
            PI, taxa, individual/tag/deployment counts, sensor types present,
            deployment time range, main location, access flags. Call this
            after list_studies when the user asks about a specific study.""")
    public Map<String, Object> get_study(
            @ToolParam(description = "Numeric Movebank study id (e.g. 2911040).")
            long study_id
    ) {
        StudyJson sj;
        try {
            sj = mirror.reader().study(String.valueOf(study_id));
        } catch (Exception e) {
            return error(e);
        }
        if (sj == null || sj.study == null) {
            return Map.of("error", "study not found", "study_id", study_id);
        }
        Map<String, String> row = sj.study;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", parseLongSafe(col(row, "id")));
        s.put("name", col(row, "name"));
        s.put("study_objective", col(row, "study_objective"));
        s.put("taxon", col(row, "taxon_ids"));
        s.put("sensor_types", col(row, "sensor_type_ids"));
        s.put("n_individuals", parseIntSafe(col(row, "number_of_individuals")));
        s.put("n_tags", parseIntSafe(col(row, "number_of_tags")));
        s.put("n_deployments", parseIntSafe(col(row, "number_of_deployments")));
        s.put("deployment_first", col(row, "timestamp_first_deployed_location"));
        s.put("deployment_last", col(row, "timestamp_last_deployed_location"));
        s.put("main_lat", parseDoubleSafe(col(row, "main_location_lat")));
        s.put("main_long", parseDoubleSafe(col(row, "main_location_long")));
        s.put("pi", col(row, "principal_investigator_name"));
        s.put("contact", col(row, "contact_person_name"));
        s.put("citation", col(row, "citation"));
        s.put("license_type", col(row, "license_type"));
        s.put("license_terms", col(row, "license_terms"));
        s.put("acknowledgements", col(row, "acknowledgements"));
        s.put("grants", col(row, "grants_used"));
        s.put("study_type", col(row, "study_type"));
        s.put("download_access", parseBoolSafe(col(row, "i_have_download_access")));
        return s;
    }

    @Tool(description = """
            List entities of a given type within a study.
            Type is one of: individuals, tags, deployments, sensors.
            Returns one row per entity with the most useful columns —
            e.g. individuals get local_identifier + taxon + sex;
            deployments get tag_id + individual_id + deploy timestamps;
            tags get manufacturer + model; sensors get tag_id +
            sensor_type_id.""")
    public Object list_entities(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(description = "One of: individuals, tags, deployments, sensors. " +
                                     "Singular forms are also accepted.")
            String type
    ) {
        // Per-type projection: entity schemas range from 3 columns for
        // sensor up to 86 for deployment; unprojected is too noisy.
        String norm = type == null ? "" : type.trim().toLowerCase().replaceAll("s$", "");
        String attrs;
        switch (norm) {
            case "individual" -> attrs = "id,local_identifier,taxon_canonical_name,sex,nick_name," +
                                         "ring_id,number_of_deployments,number_of_events,sensor_type_ids";
            case "tag" -> attrs = "id,local_identifier,manufacturer_name,model,weight," +
                                  "number_of_deployments,number_of_events,sensor_type_ids";
            case "deployment" -> attrs = "id,tag_id,individual_id,local_identifier," +
                                         "deploy_on_timestamp,deploy_off_timestamp,sensor_type_ids";
            case "sensor" -> attrs = null;  // already minimal (id, tag_id, sensor_type_id)
            default -> {
                return Map.of(
                        "error", "unknown type '" + type + "'",
                        "valid_types", List.of("individuals", "tags", "deployments", "sensors")
                );
            }
        }

        StudyJson sj;
        try {
            sj = mirror.reader().study(String.valueOf(study_id));
        } catch (Exception e) {
            return error(e);
        }
        if (sj == null) return List.of();
        List<Map<String, String>> rows = switch (norm) {
            case "individual" -> sj.individuals;
            case "tag" -> sj.tags;
            case "deployment" -> sj.deployments;
            default -> sj.sensors;
        };
        return project(rows, attrs);
    }

    @Tool(description = """
            Global catalog of Movebank sensor types (id, external_id, name,
            is_location_sensor). Use to translate between sensor type
            names ('GPS', 'acceleration') and the numeric ids that some
            endpoints require — e.g. summarize_events and query_events
            take a numeric sensor_type_id, not the human name.""")
    public Object list_sensor_types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SensorType t : SensorType.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", t.description());
            m.put("external_id", t.externalId());
            m.put("id", parseLongSafe(t.id()));
            m.put("is_location_sensor", t.isLocationSensor());
            m.put("name", t.name());
            out.add(m);
        }
        return out;
    }

    @Tool(description = """
            Free-text search over studies in the mirror. Returns the
            same compact summaries as list_studies, plus a
            'matched_in' field per hit listing which columns the query
            matched on (name / taxon / pi). Use when the user
            describes studies in natural language and you don't know
            the study id — e.g. "find albatross studies" or "studies
            by Wikelski". Case-insensitive substring match.""")
    public Object search(
            @ToolParam(description = "Free-text query, e.g. 'albatross', 'Galapagos seabirds', 'Wikelski'.")
            String query
    ) {
        if (query == null || query.isBlank()) {
            return Map.of("error", "search query cannot be empty");
        }
        // Delegate to list_studies — its filter already does the
        // case-insensitive substring match across name/taxon/pi. Then
        // annotate each hit with which columns the query hit on, so
        // the LLM can explain why each result is in the list.
        Object result = list_studies(query);
        if (!(result instanceof List<?> hits)) return result;

        String q = query.toLowerCase();
        List<Map<String, Object>> annotated = new ArrayList<>();
        for (Object o : hits) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) raw);
            List<String> matchedIn = new ArrayList<>();
            if (s.get("name")  instanceof String n && n.toLowerCase().contains(q)) matchedIn.add("name");
            if (s.get("taxon") instanceof String t && t.toLowerCase().contains(q)) matchedIn.add("taxon");
            if (s.get("pi")    instanceof String p && p.toLowerCase().contains(q)) matchedIn.add("pi");
            s.put("matched_in", matchedIn);
            annotated.add(s);
        }
        return annotated;
    }

    // ── Events (summary in context, files on request) ────────────────────

    private static final String TIME_WINDOW_HELP =
            "Time window 'from/to' in UTC, e.g. '2020-01-01/2020-12-31' or " +
            "'2008-07-11T00:00/2008-07-12T12:00'; either side may be empty. " +
            "'to' includes everything that starts with it, so '2020-12-31' " +
            "includes that whole day. Write times as above (upper-case T, " +
            "optional Z); offsets like +02:00 are not accepted. Omit for no time filter.";

    @Tool(description = """
            Summarize event data for a (study, optional tag, optional
            sensor type, optional time window): row count, column list,
            temporal extent, geographic bbox, plus first 5 and last 5
            rows as a preview. ALWAYS call this before
            download_events_to_file — it tells you whether the result
            will be 100 rows or 100 million.

            Streams the events in one pass; safe to call on large
            studies. Rows come in Movebank's order: by sensor type,
            then individual, then time. If you don't know the
            sensor_type_id, use list_sensor_types first (GPS is 653,
            acceleration 2365683).""")
    public Object summarize_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types). " +
                                                       "Omit to summarize across all sensors in the study.")
            Long sensor_type_id,
            @ToolParam(required = false, description = TIME_WINDOW_HELP)
            String time_window
    ) {
        try {
            EventSelection sel = select(study_id, tag_id, sensor_type_id, time_window);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters(study_id, tag_id, sensor_type_id, time_window));
            result.putAll(summarize(sel));
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e);
        } catch (Exception e) {
            return Map.of("error", "Could not summarize events", "details", String.valueOf(e.getMessage()));
        }
    }

    /** One pass: count rows, track first/last timestamp, min/max lat+long, capture head
     *  (first 5) and tail (last 5) without retaining the rows in between. */
    private Map<String, Object> summarize(EventSelection sel) throws Exception {
        String[] header = sel.out.columns().toArray(new String[0]);
        int tsIdx   = indexOf(header, "timestamp");
        int latIdx  = indexOf(header, "location_lat");
        int longIdx = indexOf(header, "location_long");

        long[] count = {0};
        String[] firstLast = {null, null};
        double[] box = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        boolean[] hasLat = {false}, hasLong = {false};
        List<Map<String, Object>> head = new ArrayList<>(5);
        Deque<String[]> tail = new ArrayDeque<>(6);

        sel.read(row -> {
            count[0]++;
            String ts = tsIdx >= 0 ? row[tsIdx] : null;
            if (ts != null && !ts.isEmpty()) {
                if (firstLast[0] == null) firstLast[0] = ts;
                firstLast[1] = ts;
            }
            Double lat = latIdx  >= 0 ? parseDoubleSafe(row[latIdx])  : null;
            Double lon = longIdx >= 0 ? parseDoubleSafe(row[longIdx]) : null;
            if (lat != null) { hasLat[0] = true; box[0] = Math.min(box[0], lat); box[1] = Math.max(box[1], lat); }
            if (lon != null) { hasLong[0] = true; box[2] = Math.min(box[2], lon); box[3] = Math.max(box[3], lon); }
            if (head.size() < 5) head.add(rowToTypedMap(header, row));
            tail.addLast(row);
            if (tail.size() > 5) tail.pollFirst();
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("row_count", count[0]);
        result.put("columns", sel.out.headerWritten() ? List.of(header) : List.of());
        if (!sel.out.headerWritten()) {
            result.put("head", List.of());
            result.put("tail", List.of());
            return result;
        }
        Map<String, Object> timeExtent = new LinkedHashMap<>();
        timeExtent.put("first", firstLast[0]);
        timeExtent.put("last",  firstLast[1]);
        result.put("time_extent", timeExtent);
        if (hasLat[0] && hasLong[0]) {
            result.put("bbox", Map.of(
                    "min_lat", box[0], "max_lat", box[1],
                    "min_long", box[2], "max_long", box[3]));
        } else {
            result.put("bbox", null);
        }
        result.put("head", head);
        List<Map<String, Object>> tailMaps = new ArrayList<>();
        for (String[] r : tail) tailMaps.add(rowToTypedMap(header, r));
        result.put("tail", tailMaps);
        return result;
    }

    /** Map a row to a column→value map using {@link #typedValue}'s
     *  coercion. Used by the events head/tail previews. */
    private static Map<String, Object> rowToTypedMap(String[] header, String[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            String v = (i < row.length && row[i] != null) ? row[i] : "";
            m.put(header[i], typedValue(header[i], v));
        }
        return m;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return -1;
    }

    @Tool(description = """
            Run one or more aggregations over an event-data query and
            return only the resulting scalars/bbox — never raw rows.
            One streaming pass, no row limit.

            Supported aggregation strings:

              count             → total row count (long)
              bbox              → {min_lat, max_lat, min_long, max_long}
                                  from location_lat / location_long;
                                  null if the sensor type has no location
              min:<column>      → numeric minimum of <column>
              max:<column>      → numeric maximum of <column>
              mean:<column>     → arithmetic mean of <column>

            Multiple aggregations share a single pass over the data.
            Example: ["count", "bbox", "mean:ground_speed",
            "max:ground_speed"] reads the events once.""")
    public Object query_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types).")
            Long sensor_type_id,
            @ToolParam(required = false, description = TIME_WINDOW_HELP)
            String time_window,
            @ToolParam(description = "Aggregations to compute in one pass. See the tool description for syntax.")
            List<String> aggregations
    ) {
        if (aggregations == null || aggregations.isEmpty()) {
            return Map.of("error", "at least one aggregation is required",
                          "valid_ops", List.of("count", "bbox", "min:<column>",
                                               "max:<column>", "mean:<column>"));
        }

        // Parse the requested aggregations; bail fast on syntax errors so
        // the LLM gets a clear hint rather than a partial result.
        List<AggSpec> specs = new ArrayList<>();
        for (String s : aggregations) {
            AggSpec spec = parseAggSpec(s);
            if (spec == null) {
                return Map.of(
                        "error", "unknown aggregation '" + s + "'",
                        "valid_ops", List.of("count", "bbox", "min:<column>",
                                             "max:<column>", "mean:<column>")
                );
            }
            specs.add(spec);
        }

        try {
            EventSelection sel = select(study_id, tag_id, sensor_type_id, time_window);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters(study_id, tag_id, sensor_type_id, time_window));
            result.put("aggregations", aggregate(sel, specs));
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e);
        } catch (Exception e) {
            return Map.of("error", "Could not query events", "details", String.valueOf(e.getMessage()));
        }
    }

    /** Parsed aggregation spec. {@code op} is one of count/bbox/min/max/mean;
     *  {@code column} is the column name for op∈{min,max,mean}, null for the
     *  others; {@code key} is the original input string used as the result
     *  key so the LLM sees the same names it asked for. */
    private record AggSpec(String op, String column, String key) {}

    private static AggSpec parseAggSpec(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.equals("count") || trimmed.equals("bbox")) {
            return new AggSpec(trimmed, null, trimmed);
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) return null;
        String op = trimmed.substring(0, colon).trim();
        String column = trimmed.substring(colon + 1).trim();
        if (!(op.equals("min") || op.equals("max") || op.equals("mean"))) return null;
        if (column.isEmpty()) return null;
        return new AggSpec(op, column, trimmed);
    }

    /** One pass over the events that maintains accumulators per requested
     *  aggregation and emits the final values keyed by the original input strings. */
    private Map<String, Object> aggregate(EventSelection sel, List<AggSpec> specs) throws Exception {
        String[] header = sel.out.columns().toArray(new String[0]);
        Map<String, Integer> colIdx = new LinkedHashMap<>();
        for (int i = header.length - 1; i >= 0; i--) colIdx.put(header[i], i);
        int latIdx  = colIdx.getOrDefault("location_lat",  -1);
        int longIdx = colIdx.getOrDefault("location_long", -1);

        long[] count = {0};
        // Per numeric column: [min, max, sum, n]. Shared across
        // min/max/mean specs that target the same column.
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (AggSpec spec : specs) {
            if (spec.column() != null) {
                stats.computeIfAbsent(spec.column(), c -> new double[]{
                        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 0.0});
            }
        }
        double[] box = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        boolean[] hasGeo = {false};

        sel.read(row -> {
            count[0]++;
            for (Map.Entry<String, double[]> e : stats.entrySet()) {
                int idx = colIdx.getOrDefault(e.getKey(), -1);
                if (idx < 0) continue;
                Double v = parseDoubleSafe(row[idx]);
                if (v == null) continue;
                double[] s = e.getValue();
                if (v < s[0]) s[0] = v;
                if (v > s[1]) s[1] = v;
                s[2] += v;
                s[3] += 1;
            }
            if (latIdx >= 0 && longIdx >= 0) {
                Double lat = parseDoubleSafe(row[latIdx]);
                Double lon = parseDoubleSafe(row[longIdx]);
                if (lat != null && lon != null) {
                    hasGeo[0] = true;
                    box[0] = Math.min(box[0], lat); box[1] = Math.max(box[1], lat);
                    box[2] = Math.min(box[2], lon); box[3] = Math.max(box[3], lon);
                }
            }
        });

        // Finalize — emit keyed by the LLM's original input string
        Map<String, Object> result = new LinkedHashMap<>();
        for (AggSpec spec : specs) {
            switch (spec.op()) {
                case "count" -> result.put(spec.key(), count[0]);
                case "bbox" -> result.put(spec.key(), hasGeo[0] ? Map.of(
                        "min_lat", box[0], "max_lat", box[1],
                        "min_long", box[2], "max_long", box[3]) : null);
                case "min" -> {
                    double[] s = stats.get(spec.column());
                    result.put(spec.key(), s[3] > 0 ? s[0] : null);
                }
                case "max" -> {
                    double[] s = stats.get(spec.column());
                    result.put(spec.key(), s[3] > 0 ? s[1] : null);
                }
                case "mean" -> {
                    double[] s = stats.get(spec.column());
                    result.put(spec.key(), s[3] > 0 ? s[2] / s[3] : null);
                }
            }
        }
        return result;
    }

    @Tool(description = """
            Write event data from the mirror to a CSV file on disk;
            return the path, row count, column list, and file size.
            The LLM never sees the raw rows — tell the user where the
            file is and let them analyze it with polars / pandas / R.

            ALWAYS call summarize_events first to learn how big the
            result will be — there's no row limit here.

            The file is the CSV movebank-mirror-api would send for the
            same request (Movebank's columns, order, empty missing
            values and CRLF line ends), so code written for a Movebank
            download reads it unchanged.""")
    public Object download_events_to_file(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types).")
            Long sensor_type_id,
            @ToolParam(required = false, description = TIME_WINDOW_HELP)
            String time_window,
            @ToolParam(description = "Absolute output file path. Suggest something under /tmp/ " +
                                     "unless the user has asked for a specific location. Parent " +
                                     "directories are created as needed; an existing file is " +
                                     "overwritten.")
            String path
    ) {
        if (path == null || path.isBlank()) {
            return Map.of("error", "path is required");
        }
        Path outPath = Path.of(path).toAbsolutePath();
        try {
            MirrorReader reader = mirror.reader();
            requireStudy(reader, study_id);
            EventQuery query = query(study_id, tag_id, sensor_type_id, time_window);
            List<String> columns = MovebankEventCsv.defaultColumns(
                    reader, query.studyId(), query.sensorTypeIds(), query.tagId());
            Path parent = outPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            long[] rows = {0};
            MovebankEventCsv out;
            try (BufferedWriter bw = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8);
                 CSVWriter csv = new CSVWriter(bw, CSVWriter.DEFAULT_SEPARATOR,
                         CSVWriter.DEFAULT_QUOTE_CHARACTER, CSVWriter.DEFAULT_ESCAPE_CHARACTER, "\r\n")) {
                out = new MovebankEventCsv(csv, columns);
                MovebankEventCsv writer = out;
                reader.readEvents(query, new EventVisitor() {
                    @Override public void file(EventColumns c) {
                        writer.file(c);
                    }
                    @Override public void event(Event e) throws Exception {
                        writer.event(e);
                        rows[0]++;
                    }
                });
                out.checkError(); // CSVWriter swallows write errors; don't report a truncated file as done
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters(study_id, tag_id, sensor_type_id, time_window));
            result.put("path", outPath.toString());
            result.put("row_count", rows[0]);
            result.put("columns", out.headerWritten() ? columns : List.of());
            result.put("size_bytes", Files.size(outPath));
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e);
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not download events to " + outPath,
                    "details", String.valueOf(e.getMessage())
            );
        }
    }

    // ── event selection ───────────────────────────────────────────────────

    /** A query plus the Movebank column projection its rows are delivered in. */
    private record EventSelection(MirrorReader reader, EventQuery query, MovebankEventCsv out) {

        interface RowHandler {
            void row(String[] row) throws Exception;
        }

        /** Reads the events; each row is aligned with {@code out.columns()}. */
        void read(RowHandler handler) throws Exception {
            reader.readEvents(query, new EventVisitor() {
                @Override public void file(EventColumns c) {
                    out.file(c);
                }
                @Override public void event(Event e) throws Exception {
                    handler.row(out.project(e));
                }
            });
        }
    }

    private EventSelection select(long studyId, Long tagId, Long sensorTypeId, String timeWindow) throws Exception {
        MirrorReader reader = mirror.reader();
        requireStudy(reader, studyId);
        EventQuery query = query(studyId, tagId, sensorTypeId, timeWindow);
        List<String> columns = MovebankEventCsv.defaultColumns(
                reader, query.studyId(), query.sensorTypeIds(), query.tagId());
        return new EventSelection(reader, query, new MovebankEventCsv(null, columns));
    }

    /** An unknown study is an error, not "a study without events". */
    private static void requireStudy(MirrorReader reader, long studyId) throws Exception {
        if (reader.study(String.valueOf(studyId)) == null) {
            throw new IllegalArgumentException("study " + studyId + " is not in the mirror");
        }
    }

    private static EventQuery query(long studyId, Long tagId, Long sensorTypeId, String timeWindow) {
        String[] window = parseTimeWindow(timeWindow);
        return EventQuery.study(String.valueOf(studyId))
                .sensorTypes(sensorTypeId == null ? List.of() : List.of(String.valueOf(sensorTypeId)))
                .tag(tagId == null ? null : String.valueOf(tagId))
                .between(window[0], window[1]);
    }

    private static final Pattern TIME = Pattern.compile(
            "\\d{4}(-\\d{2}(-\\d{2}([ T]\\d{2}(:\\d{2}(:\\d{2}(\\.\\d{1,3})?)?)?)?)?)?");

    /**
     * Parses "from/to" (ISO-8601 or Movebank's {@code yyyy-MM-dd HH:mm:ss.SSS}, UTC, any
     * prefix of it) into Movebank-format bounds {from, to}; null for an empty side.
     */
    static String[] parseTimeWindow(String timeWindow) {
        if (timeWindow == null || timeWindow.isBlank()) return new String[]{null, null};
        String[] parts = timeWindow.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("time_window must be 'from/to', got '" + timeWindow + "'");
        }
        String[] out = new String[2];
        for (int i = 0; i < 2; i++) {
            String t = parts[i].trim();
            if (t.endsWith("Z")) t = t.substring(0, t.length() - 1);
            if (t.isEmpty()) continue;
            if (!TIME.matcher(t).matches()) {
                throw new IllegalArgumentException("time_window: can't read '" + parts[i].trim()
                        + "'; use e.g. 2020-01-31 or 2020-01-31T12:00 (UTC)");
            }
            out[i] = t.replace('T', ' ');
        }
        return out;
    }

    private static Map<String, Object> filters(long studyId, Long tagId, Long sensorTypeId, String timeWindow) {
        // Echo what was queried so the LLM can re-cite the filters accurately.
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("study_id", studyId);
        f.put("tag_id", tagId);
        f.put("sensor_type_id", sensorTypeId);
        f.put("time_window", timeWindow);
        return f;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Map<String, Object> error(Exception e) {
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    /** A metadata value, "" when missing. */
    private static String col(Map<String, String> row, String name) {
        String v = row.get(name);
        return v == null ? "" : v;
    }

    /**
     * Rows projected to the comma-separated {@code attrs} (missing columns empty), or to the
     * union of their keys when {@code attrs} is null; values typed by {@link #typedValue}.
     */
    private static List<Map<String, Object>> project(List<Map<String, String>> rows, String attrs) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<String> cols;
        if (attrs != null) {
            cols = List.of(attrs.split(","));
        } else {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Map<String, String> r : rows) keys.addAll(r.keySet());
            cols = new ArrayList<>(keys);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String c : cols) m.put(c, typedValue(c, col(r, c)));
            out.add(m);
        }
        return out;
    }

    private static Long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static Double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }

    private static Boolean parseBoolSafe(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        return null;
    }

    /** Render a {@code "YYYY"} or {@code "YYYY–YYYY"} year range from
     *  two timestamps; null if both blank. */
    private static String yearRange(String first, String last) {
        String fy = year(first), ly = year(last);
        if (fy.isEmpty() && ly.isEmpty()) return null;
        if (fy.equals(ly) || ly.isEmpty()) return fy;
        if (fy.isEmpty()) return ly;
        return fy + "–" + ly;   // en-dash
    }

    private static String year(String ts) {
        return (ts != null && ts.length() >= 4) ? ts.substring(0, 4) : "";
    }

    /** Coerce a raw CSV value to a sensible JSON type based on column
     *  name conventions. Falls back to the raw String when the cast
     *  fails or no convention matches. */
    private static Object typedValue(String name, String value) {
        if (value == null || value.isEmpty()) return value;
        // id columns: "id" or "*_id"
        if (name.equals("id") || name.endsWith("_id")) {
            Long n = parseLongSafe(value);
            if (n != null) return n;
        }
        // counts: "number_of_*"
        if (name.startsWith("number_of_")) {
            Integer n = parseIntSafe(value);
            if (n != null) return n;
        }
        // geo + measurements (covers latitude/longitude long forms plus
        // the abbreviated suffixes used on event rows like
        // location_lat / location_long / deploy_on_lat etc.)
        if (name.equals("weight")
                || name.contains("latitude") || name.contains("longitude")
                || name.endsWith("_lat")     || name.endsWith("_long")
                || name.endsWith("_lon")) {
            Double n = parseDoubleSafe(value);
            if (n != null) return n;
        }
        // access / state flags
        if (name.startsWith("i_have_") || name.startsWith("i_can_") ||
            name.startsWith("i_am_")   || name.startsWith("is_")     ||
            name.startsWith("has_")    || name.startsWith("there_are_")) {
            Boolean b = parseBoolSafe(value);
            if (b != null) return b;
        }
        return value;
    }
}
