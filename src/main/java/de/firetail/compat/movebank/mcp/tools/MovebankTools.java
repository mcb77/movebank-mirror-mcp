package de.firetail.compat.movebank.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

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

    // ── Metadata / discovery ──────────────────────────────────────────────

    @Tool(description = """
            List studies present in the mirror. Returns compact one-line
            summaries (id, name, n_individuals, taxon, year range).
            Use this first to discover what's available before
            calling get_study or summarize_events.""")
    public List<Map<String, Object>> list_studies(
            @ToolParam(required = false,
                       description = "Free-text filter applied to study name / taxon / PI. " +
                                     "Omit to list all mirrored studies.")
            String filter
    ) {
        return List.of(
                Map.of("stub", true,
                       "message", "list_studies — implementation pending",
                       "filter", filter == null ? "(none)" : filter)
        );
    }

    @Tool(description = """
            Full metadata for one study: citation, PIs, license terms, taxa
            list, temporal/geographic extent, sensor types present,
            individual + tag counts.""")
    public Map<String, Object> get_study(
            @ToolParam(description = "Numeric Movebank study id (e.g. 2911040).")
            long study_id
    ) {
        return Map.of("stub", true,
                      "message", "get_study — implementation pending",
                      "study_id", study_id);
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
}
