package de.firetail.compat.movebank.mcp.tools;

import com.opencsv.CSVReader;
import de.firetail.compat.movebank.mcp.MirrorApiClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.StringReader;
import java.util.ArrayList;
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
            Use to enumerate what's in a study at the level of detail
            the user asks about.""")
    public List<Map<String, Object>> list_entities(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(description = "One of: individuals, tags, deployments, sensors.")
            String type
    ) {
        return List.of(
                Map.of("stub", true,
                       "message", "list_entities — implementation pending",
                       "study_id", study_id,
                       "type", type)
        );
    }

    @Tool(description = """
            Global catalog of Movebank sensor types (id, name, description).
            Use to translate between sensor type names ('GPS', 'acceleration')
            and the numeric ids that some endpoints require.""")
    public List<Map<String, Object>> list_sensor_types() {
        return List.of(
                Map.of("stub", true,
                       "message", "list_sensor_types — implementation pending")
        );
    }

    @Tool(description = """
            Fuzzy free-text search over study names, taxa, principal
            investigators, and locations. Use when the user describes
            studies in natural language and you don't know the study id.""")
    public List<Map<String, Object>> search(
            @ToolParam(description = "Free-text query, e.g. 'albatross', 'Galapagos seabirds', 'Wikelski'.")
            String query
    ) {
        return List.of(
                Map.of("stub", true,
                       "message", "search — implementation pending",
                       "query", query)
        );
    }

    // ── Events (summary in context, files on request) ────────────────────

    @Tool(description = """
            Summarize event data for a (study, optional tag, optional sensor
            type, optional time window): row count, columns, temporal
            extent, geographic bbox, plus first 5 and last 5 rows as a
            preview. ALWAYS call this before download_events_to_file —
            it tells you whether the result will be 100 rows or 100 million.""")
    public Map<String, Object> summarize_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id (see list_sensor_types).")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window, e.g. '2020-01-01/2020-12-31'.")
            String time_window
    ) {
        return Map.of("stub", true,
                      "message", "summarize_events — implementation pending",
                      "study_id", study_id);
    }

    @Tool(description = """
            Run a bounded aggregation over events and return scalars or a
            small table: counts, means, percentiles, bounding boxes.
            Refuses queries that would return more than ~1000 rows.
            Use for 'what's the mean speed of tag X in spring 2024' type
            questions without producing a file.""")
    public Map<String, Object> query_events(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id.")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window.")
            String time_window,
            @ToolParam(description = "Aggregation spec, e.g. 'count', 'mean:ground_speed', 'bbox', 'percentiles:ground_speed'.")
            String aggregation
    ) {
        return Map.of("stub", true,
                      "message", "query_events — implementation pending",
                      "study_id", study_id,
                      "aggregation", aggregation);
    }

    @Tool(description = """
            Produce a CSV file of event data on disk; return the path,
            row count, and column list. The LLM never sees the raw rows —
            tell the user where the file is and let them analyze it with
            polars / pandas / R. Default format is CSV; use this when
            summarize_events tells you the result is too big for context.""")
    public Map<String, Object> download_events_to_file(
            @ToolParam(description = "Numeric Movebank study id.")
            long study_id,
            @ToolParam(required = false, description = "Restrict to one tag id.")
            Long tag_id,
            @ToolParam(required = false, description = "Restrict to one sensor type id.")
            Long sensor_type_id,
            @ToolParam(required = false, description = "ISO-8601 time window.")
            String time_window,
            @ToolParam(description = "Absolute output file path. Suggest something under /tmp/ unless the user has asked for a specific location.")
            String path
    ) {
        return Map.of("stub", true,
                      "message", "download_events_to_file — implementation pending",
                      "study_id", study_id,
                      "path", path);
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
}
