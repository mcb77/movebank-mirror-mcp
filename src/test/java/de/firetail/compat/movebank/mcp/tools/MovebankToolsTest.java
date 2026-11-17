package de.firetail.compat.movebank.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.mcp.MirrorDirectory;
import de.firetail.compat.movebank.mirror.StudyJson;
import de.firetail.compat.movebank.mirror.reader.MirrorLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools against a small mirror on disk; no Spring context needed. */
class MovebankToolsTest {

    @TempDir Path dir;
    private MovebankTools tools;

    @BeforeEach
    void setUp() throws Exception {
        StudyJson s = new StudyJson();
        s.study = Map.of("id", "42", "name", "Test gulls", "number_of_individuals", "1");
        s.deployments = List.of(Map.of("id", "301", "tag_id", "10", "individual_id", "7",
                "deploy_on_timestamp", "2020-01-01 00:00:00.000", "deploy_off_timestamp", ""));
        s.tags = List.of(); s.individuals = List.of(); s.sensors = List.of();
        s.sensorTypeIds = Set.of("653");
        s.attributesBySensorTypeIDs = Map.of();
        new ObjectMapper().writeValue(MirrorLayout.studyFile(dir.toFile(), "42"), s);
        Path tag = MirrorLayout.studyDir(dir.toFile(), "42").toPath().resolve("10");
        Files.createDirectories(tag);
        Files.writeString(tag.resolve("653_20200101000000.csv"), String.join("\n",
                "timestamp,location_lat,location_long",
                "2020-03-01 00:00:00.000,1.0,10.0",
                "2020-03-02 12:00:00.000,2.0,11.0",
                "2020-03-03 00:00:00.000,3.0,12.0", ""));
        tools = new MovebankTools(new MirrorDirectory(dir.toString()));
    }

    @Test
    void parseTimeWindow() {
        assertArrayEquals(new String[]{"2020-01-01", "2020-12-31"}, MovebankTools.parseTimeWindow("2020-01-01/2020-12-31"));
        assertArrayEquals(new String[]{"2008-07-11 00:00", null}, MovebankTools.parseTimeWindow("2008-07-11T00:00Z/"));
        assertArrayEquals(new String[]{null, null}, MovebankTools.parseTimeWindow(" "));
        assertThrows(IllegalArgumentException.class, () -> MovebankTools.parseTimeWindow("last week"));
        assertThrows(IllegalArgumentException.class, () -> MovebankTools.parseTimeWindow("2020-01-01+02:00/"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeWindowFiltersEvents() {
        Map<String, Object> r = (Map<String, Object>) tools.query_events(42, null, null,
                "2020-03-02/2020-03-02", List.of("count", "mean:location_lat"));
        Map<String, Object> agg = (Map<String, Object>) r.get("aggregations");
        assertEquals(1L, agg.get("count"));
        assertEquals(2.0, agg.get("mean:location_lat"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invertedTimeWindowIsAnError() {
        Map<String, Object> r = (Map<String, Object>) tools.summarize_events(42, null, null, "2021/2020");
        assertTrue(String.valueOf(r.get("error")).contains("starts after it ends"), r.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownStudyIsAnErrorNotAnEmptyResult() {
        Map<String, Object> r = (Map<String, Object>) tools.summarize_events(999, null, null, null);
        assertTrue(String.valueOf(r.get("error")).contains("not in the mirror"), r.toString());
        r = (Map<String, Object>) tools.download_events_to_file(999, null, null, null, dir.resolve("x.csv").toString());
        assertTrue(String.valueOf(r.get("error")).contains("not in the mirror"), r.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadWritesMovebankCsv() throws Exception {
        Path out = dir.resolve("out/gps.csv");
        Map<String, Object> r = (Map<String, Object>) tools.download_events_to_file(42, null, 653L, null, out.toString());
        assertEquals(3L, r.get("row_count"), r.toString());
        List<String> lines = Files.readAllLines(out);
        assertEquals("timestamp,location_lat,location_long,tag_id,individual_id,deployment_id,event_id", lines.get(0));
        assertEquals("2020-03-01 00:00:00.000,1.0,10.0,10,7,301,1", lines.get(1));
        assertTrue(Files.readString(out).contains("\r\n"), "CRLF like Movebank");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingMirrorDirectoryIsReported() {
        MovebankTools none = new MovebankTools(new MirrorDirectory(""));
        Map<String, Object> r = (Map<String, Object>) none.summarize_events(42, null, null, null);
        assertTrue(String.valueOf(r.get("error")).contains("MOVEBANK_MIRROR_DIR"), r.toString());
    }
}
