package ch.uzh.ifi.hase.soprafs26.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

@RestController
public class BuildInfoController {

    private static final String UNKNOWN_COMMIT = "unknown";
    private static final String UNKNOWN_DATE = "--------";
    private static final String UNKNOWN_TIME = "--:--";

    private static final String[] COMMIT_ENV_CANDIDATES = {
        "CABO_SERVER_BUILD_COMMIT_ID",
        "CABO_SERVER_GIT_COMMIT_SHA",
        "GITHUB_SHA",
        "CI_COMMIT_SHA"
    };
    private static final String[] TIMESTAMP_ENV_CANDIDATES = {
        "CABO_SERVER_BUILD_COMMIT_TIMESTAMP",
        "CABO_SERVER_GIT_COMMIT_TIMESTAMP",
        "CI_COMMIT_TIMESTAMP"
    };
    private static final String[] COMMIT_PROPERTY_CANDIDATES = {
        "commitId",
        "build.commitId",
        "build.commit.id",
        "build.commit",
        "git.commit.id.abbrev",
        "git.commit.id"
    };
    private static final String[] TIMESTAMP_PROPERTY_CANDIDATES = {
        "timestamp",
        "build.timestamp",
        "build.time",
        "git.commit.time",
        "git.build.time"
    };
    private static final String[] CLASSPATH_BUILD_INFO_FILES = {
        "build-info.properties",
        "git.properties",
        "META-INF/build-info.properties",
        "META-INF/git.properties"
    };

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<Properties> DEFAULT_BUILD_PROPERTIES = loadBuildProperties();

    private final Supplier<Map<String, String>> envSupplier;
    private final Supplier<List<Properties>> buildPropertiesSupplier;

    public BuildInfoController() {
        this(System::getenv, () -> DEFAULT_BUILD_PROPERTIES);
    }

    BuildInfoController(
        Supplier<Map<String, String>> envSupplier,
        Supplier<List<Properties>> buildPropertiesSupplier
    ) {
        this.envSupplier = envSupplier;
        this.buildPropertiesSupplier = buildPropertiesSupplier;
    }

    @GetMapping("/build-info")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public Map<String, String> getBuildInfo() {
        Map<String, String> env = safeMap(envSupplier.get());
        List<Properties> buildProperties = safePropertiesList(buildPropertiesSupplier.get());

        String commitId = firstNonBlank(
            firstNonBlankFromEnvironment(env, COMMIT_ENV_CANDIDATES),
            firstNonBlankProperty(buildProperties, COMMIT_PROPERTY_CANDIDATES),
            firstNonBlankFromEnvironment(env, "GAE_VERSION")
        );
        if (commitId == null) {
            return unknownBuildInfo();
        }

        String timestamp = firstNonBlank(
            firstNonBlankFromEnvironment(env, TIMESTAMP_ENV_CANDIDATES),
            firstNonBlankProperty(buildProperties, TIMESTAMP_PROPERTY_CANDIDATES),
            firstNonBlankFromEnvironment(env, "GAE_DEPLOYMENT_ID")
        );
        LocalDateTime parsedTimestamp = parseTimestamp(timestamp);

        if (parsedTimestamp == null) {
            return Map.of(
                "commitId", commitId,
                "date", UNKNOWN_DATE,
                "time", UNKNOWN_TIME
            );
        }

        return Map.of(
            "commitId", commitId,
            "date", DATE_FORMATTER.format(parsedTimestamp),
            "time", TIME_FORMATTER.format(parsedTimestamp)
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static String firstNonBlankFromEnvironment(Map<String, String> env, String... keys) {
        if (env == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = env.get(key);
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static String firstNonBlankProperty(List<Properties> propertySources, String... keys) {
        if (propertySources == null || keys == null) {
            return null;
        }

        for (Properties propertySource : propertySources) {
            if (propertySource == null) {
                continue;
            }

            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                String value = propertySource.getProperty(key);
                if (value != null) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }
        }
        return null;
    }

    private static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException ignored) {
            // try next parser
        }

        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (RuntimeException ignored) {
            // try next parser
        }

        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ignored) {
            // try next parser
        }

        if (!value.matches("\\d+")) {
            return null;
        }

        try {
            long epoch = Long.parseLong(value);
            if (value.length() >= 13) {
                return Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime();
            }
            return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<Properties> loadBuildProperties() {
        List<Properties> resolvedProperties = new ArrayList<>();
        ClassLoader classLoader = BuildInfoController.class.getClassLoader();

        for (String resourceName : CLASSPATH_BUILD_INFO_FILES) {
            if (resourceName == null || resourceName.isBlank()) {
                continue;
            }

            try (InputStream resourceStream = classLoader.getResourceAsStream(resourceName)) {
                if (resourceStream == null) {
                    continue;
                }

                Properties properties = new Properties();
                properties.load(resourceStream);
                resolvedProperties.add(properties);
            } catch (IOException ignored) {
                // keep fallback chain resilient
            }
        }

        return resolvedProperties;
    }

    private static Map<String, String> safeMap(Map<String, String> source) {
        return source == null ? Collections.emptyMap() : source;
    }

    private static List<Properties> safePropertiesList(List<Properties> source) {
        return source == null ? List.of() : source;
    }

    private static Map<String, String> unknownBuildInfo() {
        return Map.of(
            "commitId", UNKNOWN_COMMIT,
            "date", UNKNOWN_DATE,
            "time", UNKNOWN_TIME
        );
    }
}
