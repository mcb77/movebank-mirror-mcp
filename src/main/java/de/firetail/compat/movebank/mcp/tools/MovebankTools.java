package de.firetail.compat.movebank.mcp.tools;

import com.opencsv.CSVReader;
import de.firetail.compat.movebank.mcp.MirrorApiClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 8 LLM-callable tools exposed by movebank-mcp. v0.0.1 stubs:
 * each method returns a placeholder so the MCP client (Inspector,
 * Claude Code) can see the tool schemas. Real implementations follow.
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
 * v1 talks to a local movebank-mirror-api (default: http://localhost:8080).
 * Live-API fallback is a v0.2.0 concern.
 */
@Service
public class MovebankTools {

    private final MirrorApiClient mirror;

    public MovebankTools(MirrorApiClient mirror) {
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
        String csv;
        try {
            csv = mirror.directRead(Map.of(
                    "entity_type", "study",
                    "attributes",
                    "id,name,taxon_ids,number_of_individuals," +
                    "timestamp_first_deployed_location,timestamp_last_deployed_location," +
                    "principal_investigator_name,sensor_type_ids"
            ));
        } catch (RestClientException e) {
            return Map.of(
                    "error", "Could not reach movebank-mirror-api at " + mirror.baseUrl() +
                             " — is it running?",
                    "details", e.getMessage()
            );
        }

        List<Map<String, Object>> studies = new ArrayList<>();
        String f = (filter == null || filter.isBlank()) ? null : filter.toLowerCase();
        try (CSVReader r = new CSVReader(new StringReader(csv))) {
            String[] header = r.readNext();
            if (header == null) return studies;
            String[] row;
            while ((row = r.readNext()) != null) {
                String name = col(row, header, "name");
                String taxon = col(row, header, "taxon_ids");
                String pi = col(row, header, "principal_investigator_name");
                if (f != null && !(name.toLowerCase().contains(f) ||
                                   taxon.toLowerCase().contains(f) ||
                                   pi.toLowerCase().contains(f))) {
                    continue;
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", parseLongSafe(col(row, header, "id")));
                s.put("name", name);
                s.put("taxon", taxon);
                s.put("n_individuals", parseIntSafe(col(row, header, "number_of_individuals")));
                s.put("deployment_year_range", yearRange(
                        col(row, header, "timestamp_first_deployed_location"),
                        col(row, header, "timestamp_last_deployed_location")));
                s.put("sensor_types", col(row, header, "sensor_type_ids"));
                s.put("pi", pi);
                studies.add(s);
            }
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not parse mirror-api response as CSV",
                    "details", e.getMessage()
            );
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
        String csv;
        try {
            csv = mirror.directRead(Map.of(
                    "entity_type", "study",
                    "id", String.valueOf(study_id)
            ));
        } catch (RestClientException e) {
            return Map.of(
                    "error", "Could not reach movebank-mirror-api at " + mirror.baseUrl() +
                             " — is it running?",
                    "details", e.getMessage()
            );
        }

        try (CSVReader r = new CSVReader(new StringReader(csv))) {
            String[] header = r.readNext();
            String[] row = header == null ? null : r.readNext();
            if (row == null) {
                return Map.of("error", "study not found", "study_id", study_id);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", parseLongSafe(col(row, header, "id")));
            s.put("name", col(row, header, "name"));
            s.put("study_objective", col(row, header, "study_objective"));
            s.put("taxon", col(row, header, "taxon_ids"));
            s.put("sensor_types", col(row, header, "sensor_type_ids"));
            s.put("n_individuals", parseIntSafe(col(row, header, "number_of_individuals")));
            s.put("n_tags", parseIntSafe(col(row, header, "number_of_tags")));
            s.put("n_deployments", parseIntSafe(col(row, header, "number_of_deployments")));
            s.put("deployment_first", col(row, header, "timestamp_first_deployed_location"));
            s.put("deployment_last", col(row, header, "timestamp_last_deployed_location"));
            s.put("main_lat", parseDoubleSafe(col(row, header, "main_location_lat")));
            s.put("main_long", parseDoubleSafe(col(row, header, "main_location_long")));
            s.put("pi", col(row, header, "principal_investigator_name"));
            s.put("contact", col(row, header, "contact_person_name"));
            s.put("citation", col(row, header, "citation"));
            s.put("license_type", col(row, header, "license_type"));
            s.put("license_terms", col(row, header, "license_terms"));
            s.put("acknowledgements", col(row, header, "acknowledgements"));
            s.put("grants", col(row, header, "grants_used"));
            s.put("study_type", col(row, header, "study_type"));
            s.put("download_access", parseBoolSafe(col(row, header, "i_have_download_access")));
            return s;
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not parse mirror-api response as CSV",
                    "details", e.getMessage()
            );
        }
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
        // Normalize plural → singular for mirror-api's entity_type, and
        // pick a per-type projection (entity schemas range from 3 columns
        // for sensor up to 86 for deployment — unprojected is too noisy).
        String norm = type == null ? "" : type.trim().toLowerCase().replaceAll("s$", "");
        String entityType;
        String attrs;
        switch (norm) {
            case "individual" -> {
                entityType = "individual";
                attrs = "id,local_identifier,taxon_canonical_name,sex,nick_name," +
                        "ring_id,number_of_deployments,number_of_events,sensor_type_ids";
            }
            case "tag" -> {
                entityType = "tag";
                attrs = "id,local_identifier,manufacturer_name,model,weight," +
                        "number_of_deployments,number_of_events,sensor_type_ids";
            }
            case "deployment" -> {
                entityType = "deployment";
                attrs = "id,tag_id,individual_id,local_identifier," +
                        "deploy_on_timestamp,deploy_off_timestamp,sensor_type_ids";
            }
            case "sensor" -> {
                entityType = "sensor";
                attrs = null;  // already minimal (id, tag_id, sensor_type_id)
            }
            default -> {
                return Map.of(
                        "error", "unknown type '" + type + "'",
                        "valid_types", List.of("individuals", "tags", "deployments", "sensors")
                );
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("entity_type", entityType);
        params.put("study_id", String.valueOf(study_id));
        if (attrs != null) params.put("attributes", attrs);

        String csv;
        try {
            csv = mirror.directRead(params);
        } catch (RestClientException e) {
            return Map.of(
                    "error", "Could not reach movebank-mirror-api at " + mirror.baseUrl() +
                             " — is it running?",
                    "details", e.getMessage()
            );
        }

        try {
            return parseCsvAsListOfMaps(csv);
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not parse mirror-api response as CSV",
                    "details", e.getMessage()
            );
        }
    }

    @Tool(description = """
            Global catalog of Movebank sensor types (id, external_id, name,
            is_location_sensor). Use to translate between sensor type
            names ('GPS', 'acceleration') and the numeric ids that some
            endpoints require — e.g. summarize_events and query_events
            take a numeric sensor_type_id, not the human name.""")
    public Object list_sensor_types() {
        String csv;
        try {
            csv = mirror.directRead(Map.of("entity_type", "tag_type"));
        } catch (RestClientException e) {
            return Map.of(
                    "error", "Could not reach movebank-mirror-api at " + mirror.baseUrl() +
                             " — is it running?",
                    "details", e.getMessage()
            );
        }
        try {
            return parseCsvAsListOfMaps(csv);
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not parse mirror-api response as CSV",
                    "details", e.getMessage()
            );
        }
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

    @Tool(description = """
            Summarize event data for a (study, optional tag, optional
            sensor type, optional time window): row count, column list,
            temporal extent, geographic bbox, plus first 5 and last 5
            rows as a preview. ALWAYS call this before
            download_events_to_file — it tells you whether the result
            will be 100 rows or 100 million.

            Streams the response in one pass; safe to call on large
            studies. If you don't know the sensor_type_id, use
            list_sensor_types first (GPS is 653, acceleration 2365683).""")
    public Object summarize_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types). " +
                                                       "Omit to summarize across all sensors in the study.")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window 'from/to', e.g. " +
                                                       "'2020-01-01/2020-12-31'. Omit for no time filter.")
            String time_window
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("entity_type", "event");
        params.put("study_id", String.valueOf(study_id));
        params.put("attributes", "all");
        if (sensor_type_id != null) params.put("sensor_type_id", String.valueOf(sensor_type_id));
        if (tag_id != null)         params.put("tag_id", String.valueOf(tag_id));
        applyTimeWindow(params, time_window);

        try {
            Map<String, Object> summary = mirror.directReadStreaming(params, this::summarizeStream);
            // Echo what was queried so the LLM can re-cite the filters
            // accurately in its response to the user.
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("study_id", study_id);
            filters.put("tag_id", tag_id);
            filters.put("sensor_type_id", sensor_type_id);
            filters.put("time_window", time_window);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters);
            result.putAll(summary);
            return result;
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not summarize events from mirror-api at " + mirror.baseUrl(),
                    "details", e.getMessage()
            );
        }
    }

    /** One streaming pass: count rows, track first/last timestamp,
     *  min/max lat+long, capture head (first 5) and tail (last 5)
     *  without retaining the rows in between. */
    private Map<String, Object> summarizeStream(Reader csv) {
        try (CSVReader r = new CSVReader(csv)) {
            String[] header = r.readNext();
            if (header == null) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("row_count", 0L);
                empty.put("columns", List.of());
                empty.put("head", List.of());
                empty.put("tail", List.of());
                return empty;
            }
            int tsIdx   = indexOf(header, "timestamp");
            int latIdx  = indexOf(header, "location_lat");
            int longIdx = indexOf(header, "location_long");

            long count = 0;
            String firstTs = null, lastTs = null;
            Double minLat = null, maxLat = null, minLong = null, maxLong = null;
            List<Map<String, Object>> head = new ArrayList<>(5);
            Deque<Map<String, Object>> tail = new ArrayDeque<>(6);

            String[] row;
            while ((row = r.readNext()) != null) {
                count++;
                if (tsIdx >= 0 && tsIdx < row.length) {
                    String ts = row[tsIdx];
                    if (ts != null && !ts.isEmpty()) {
                        if (firstTs == null) firstTs = ts;
                        lastTs = ts;
                    }
                }
                Double lat = (latIdx  >= 0 && latIdx  < row.length) ? parseDoubleSafe(row[latIdx])  : null;
                Double lon = (longIdx >= 0 && longIdx < row.length) ? parseDoubleSafe(row[longIdx]) : null;
                if (lat != null) { if (minLat  == null || lat < minLat)  minLat  = lat;
                                   if (maxLat  == null || lat > maxLat)  maxLat  = lat; }
                if (lon != null) { if (minLong == null || lon < minLong) minLong = lon;
                                   if (maxLong == null || lon > maxLong) maxLong = lon; }

                if (head.size() < 5) head.add(rowToTypedMap(header, row));
                tail.addLast(rowToTypedMap(header, row));
                if (tail.size() > 5) tail.pollFirst();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("row_count", count);
            result.put("columns", List.of(header));
            Map<String, Object> timeExtent = new LinkedHashMap<>();
            timeExtent.put("first", firstTs);
            timeExtent.put("last",  lastTs);
            result.put("time_extent", timeExtent);
            if (minLat != null && minLong != null) {
                result.put("bbox", Map.of(
                        "min_lat", minLat, "max_lat", maxLat,
                        "min_long", minLong, "max_long", maxLong));
            } else {
                result.put("bbox", null);
            }
            result.put("head", head);
            result.put("tail", new ArrayList<>(tail));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Could not parse event CSV stream", e);
        }
    }

    /** Parse a "from/to" ISO-8601 window and stash as Movebank's
     *  separate timestamp_start / timestamp_end query parameters.
     *  Skips silently if the input doesn't have a slash. */
    private static void applyTimeWindow(Map<String, String> params, String timeWindow) {
        if (timeWindow == null || timeWindow.isBlank()) return;
        String[] parts = timeWindow.split("/", 2);
        if (parts.length == 2) {
            String from = parts[0].trim();
            String to   = parts[1].trim();
            if (!from.isEmpty()) params.put("timestamp_start", from);
            if (!to.isEmpty())   params.put("timestamp_end",   to);
        }
    }

    /** Map a CSV row to a column→value map using {@link #typedValue}'s
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
            "max:ground_speed"] runs in one HTTP call.""")
    public Object query_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types).")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window 'from/to'.")
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

        Map<String, String> params = new LinkedHashMap<>();
        params.put("entity_type", "event");
        params.put("study_id", String.valueOf(study_id));
        params.put("attributes", "all");
        if (sensor_type_id != null) params.put("sensor_type_id", String.valueOf(sensor_type_id));
        if (tag_id != null)         params.put("tag_id", String.valueOf(tag_id));
        applyTimeWindow(params, time_window);

        try {
            Map<String, Object> aggregates =
                    mirror.directReadStreaming(params, csv -> aggregateStream(csv, specs));
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("study_id", study_id);
            filters.put("tag_id", tag_id);
            filters.put("sensor_type_id", sensor_type_id);
            filters.put("time_window", time_window);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters);
            result.put("aggregations", aggregates);
            return result;
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not query events from mirror-api",
                    "details", e.getMessage()
            );
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

    /** One streaming pass over an event-data CSV that maintains
     *  accumulators per requested aggregation and emits the final
     *  values keyed by the original input strings. */
    private Map<String, Object> aggregateStream(Reader csv, List<AggSpec> specs) {
        try (CSVReader r = new CSVReader(csv)) {
            String[] header = r.readNext();
            if (header == null) {
                // Empty response → all counts/aggregates are zero/null
                Map<String, Object> empty = new LinkedHashMap<>();
                for (AggSpec spec : specs) {
                    empty.put(spec.key(), spec.op().equals("count") ? 0L : null);
                }
                return empty;
            }

            // Column index lookups (one-time)
            Map<String, Integer> colIdx = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) colIdx.put(header[i], i);
            int latIdx  = colIdx.getOrDefault("location_lat",  -1);
            int longIdx = colIdx.getOrDefault("location_long", -1);

            // Accumulators
            long count = 0;
            // Per numeric column: [min, max, sum, n]. Shared across
            // min/max/mean specs that target the same column.
            Map<String, double[]> stats = new LinkedHashMap<>();
            for (AggSpec spec : specs) {
                if (spec.column() != null) {
                    stats.computeIfAbsent(spec.column(), c -> new double[]{
                            Double.POSITIVE_INFINITY,
                            Double.NEGATIVE_INFINITY,
                            0.0, 0.0
                    });
                }
            }
            double minLat = Double.POSITIVE_INFINITY, maxLat  = Double.NEGATIVE_INFINITY;
            double minLong = Double.POSITIVE_INFINITY, maxLong = Double.NEGATIVE_INFINITY;
            boolean hasGeo = false;

            String[] row;
            while ((row = r.readNext()) != null) {
                count++;
                for (Map.Entry<String, double[]> e : stats.entrySet()) {
                    int idx = colIdx.getOrDefault(e.getKey(), -1);
                    if (idx < 0 || idx >= row.length) continue;
                    Double v = parseDoubleSafe(row[idx]);
                    if (v == null) continue;
                    double[] s = e.getValue();
                    if (v < s[0]) s[0] = v;
                    if (v > s[1]) s[1] = v;
                    s[2] += v;
                    s[3] += 1;
                }
                if (latIdx >= 0 && longIdx >= 0
                        && latIdx < row.length && longIdx < row.length) {
                    Double lat = parseDoubleSafe(row[latIdx]);
                    Double lon = parseDoubleSafe(row[longIdx]);
                    if (lat != null && lon != null) {
                        hasGeo = true;
                        if (lat < minLat)  minLat  = lat;
                        if (lat > maxLat)  maxLat  = lat;
                        if (lon < minLong) minLong = lon;
                        if (lon > maxLong) maxLong = lon;
                    }
                }
            }

            // Finalize — emit keyed by the LLM's original input string
            Map<String, Object> result = new LinkedHashMap<>();
            for (AggSpec spec : specs) {
                switch (spec.op()) {
                    case "count" -> result.put(spec.key(), count);
                    case "bbox" -> result.put(spec.key(), hasGeo ? Map.of(
                            "min_lat", minLat, "max_lat", maxLat,
                            "min_long", minLong, "max_long", maxLong) : null);
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
        } catch (Exception e) {
            throw new RuntimeException("Could not aggregate event CSV stream", e);
        }
    }

    @Tool(description = """
            Stream event data from the mirror to a CSV file on disk;
            return the path, row count, column list, and file size.
            The LLM never sees the raw rows — tell the user where the
            file is and let them analyze it with polars / pandas / R.

            ALWAYS call summarize_events first to learn how big the
            result will be — there's no row limit here.

            Bytes are passed through unchanged from mirror-api so the
            CSV is bit-faithful with what the live Movebank API would
            return; downstream tools that depend on specific quoting
            (notably R's read.csv with na.strings) behave identically.""")
    public Object download_events_to_file(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types).")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window 'from/to'.")
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

        Map<String, String> params = new LinkedHashMap<>();
        params.put("entity_type", "event");
        params.put("study_id", String.valueOf(study_id));
        params.put("attributes", "all");
        if (sensor_type_id != null) params.put("sensor_type_id", String.valueOf(sensor_type_id));
        if (tag_id != null)         params.put("tag_id", String.valueOf(tag_id));
        applyTimeWindow(params, time_window);

        try {
            Map<String, Object> summary =
                    mirror.directReadStreaming(params, csv -> streamCsvToFile(csv, outPath));
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("study_id", study_id);
            filters.put("tag_id", tag_id);
            filters.put("sensor_type_id", sensor_type_id);
            filters.put("time_window", time_window);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filters", filters);
            result.putAll(summary);
            return result;
        } catch (Exception e) {
            return Map.of(
                    "error", "Could not download events to " + outPath,
                    "details", e.getMessage()
            );
        }
    }

    /** Stream a CSV response line-by-line to a local file. Counts data
     *  rows and parses the header for the column list, but does NOT
     *  re-encode the CSV — the bytes (modulo newline normalization)
     *  are passed through unchanged, preserving mirror-api's
     *  applyQuotesToAll=false quoting that downstream R clients rely on. */
    private Map<String, Object> streamCsvToFile(Reader csv, Path outPath) {
        try {
            Path parent = outPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            long rowCount = 0;
            String[] columns = new String[0];

            try (BufferedReader br = new BufferedReader(csv);
                 BufferedWriter bw = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {

                String headerLine = br.readLine();
                if (headerLine != null) {
                    bw.write(headerLine);
                    bw.newLine();
                    // Parse the header just to extract column names for the
                    // response. CSVReader handles quoted commas correctly.
                    try (CSVReader hr = new CSVReader(new StringReader(headerLine))) {
                        String[] parsed = hr.readNext();
                        if (parsed != null) columns = parsed;
                    }
                }

                String line;
                while ((line = br.readLine()) != null) {
                    bw.write(line);
                    bw.newLine();
                    rowCount++;
                }
            }

            long size = Files.size(outPath);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", outPath.toString());
            result.put("row_count", rowCount);
            result.put("columns", List.of(columns));
            result.put("size_bytes", size);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Could not write to " + outPath + ": " + e.getMessage(), e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Look up a column by header name, returning "" when the header
     *  or value is missing. CSV from mirror-api always uses our
     *  requested attribute order, but defensive lookup is cheap. */
    private static String col(String[] row, String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) {
                return i < row.length && row[i] != null ? row[i] : "";
            }
        }
        return "";
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

    /** Parse a mirror-api CSV response into rows of column→value maps.
     *  Empty / missing values stay as empty strings; values get
     *  coerced to numeric / boolean based on the column name
     *  (see {@link #typedValue}). */
    private static List<Map<String, Object>> parseCsvAsListOfMaps(String csv) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CSVReader r = new CSVReader(new StringReader(csv))) {
            String[] header = r.readNext();
            if (header == null) return rows;
            String[] row;
            while ((row = r.readNext()) != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    String v = (i < row.length && row[i] != null) ? row[i] : "";
                    m.put(header[i], typedValue(header[i], v));
                }
                rows.add(m);
            }
        }
        return rows;
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
