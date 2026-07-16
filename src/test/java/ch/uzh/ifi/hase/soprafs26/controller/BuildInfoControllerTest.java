package ch.uzh.ifi.hase.soprafs26.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildInfoControllerTest {

    @Test
    void getBuildInfo_prefersExplicitEnvironmentValues() {
        BuildInfoController controller = new BuildInfoController(
            () -> Map.of(
                "CABO_SERVER_BUILD_COMMIT_ID", "abc123",
                "CABO_SERVER_BUILD_COMMIT_TIMESTAMP", "2026-05-20T18:45:00Z"
            ),
            List::of
        );

        Map<String, String> result = controller.getBuildInfo();

        assertEquals("abc123", result.get("commitId"));
        assertEquals("20052026", result.get("date"));
        assertEquals("18:45", result.get("time"));
    }

    @Test
    void getBuildInfo_usesClasspathPropertiesWhenEnvIsMissing() {
        Properties properties = new Properties();
        properties.setProperty("git.commit.id.abbrev", "deadbee");
        properties.setProperty("git.commit.time", "2026-05-21T09:10:00Z");

        BuildInfoController controller = new BuildInfoController(
            Map::of,
            () -> List.of(properties)
        );

        Map<String, String> result = controller.getBuildInfo();

        assertEquals("deadbee", result.get("commitId"));
        assertEquals("21052026", result.get("date"));
        assertEquals("09:10", result.get("time"));
    }

    @Test
    void getBuildInfo_returnsUnknownWhenNoSourceIsAvailable() {
        BuildInfoController controller = new BuildInfoController(Map::of, List::of);

        Map<String, String> result = controller.getBuildInfo();

        assertEquals("unknown", result.get("commitId"));
        assertEquals("--------", result.get("date"));
        assertEquals("--:--", result.get("time"));
    }
}
