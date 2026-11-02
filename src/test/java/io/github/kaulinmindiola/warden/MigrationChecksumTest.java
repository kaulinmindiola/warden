package io.github.kaulinmindiola.warden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * REQ-CM-003: a migration merged into the main branch is never modified. Every migration is
 * recorded with the SHA-256 of its content in {@code src/test/resources/db/migration-checksums.txt};
 * the build fails when a recorded migration changes or disappears, or when a migration is not
 * recorded. Flyway's {@code validate-on-migrate} only catches a change against a database that
 * already applied the original, which a fresh test database never has.
 *
 * <p>Record new migrations, and only new ones, with:
 *
 * <pre>
 * ./mvnw verify -DskipITs=true -Dtest=MigrationChecksumTest -Dmigrations.record=true
 * </pre>
 */
class MigrationChecksumTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path MANIFEST = Path.of("src", "test", "resources", "db", "migration-checksums.txt");
    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__\\w+\\.sql");
    private static final Comparator<String> BY_VERSION =
            Comparator.comparingInt(MigrationChecksumTest::version).thenComparing(Comparator.naturalOrder());

    @Test
    @Verifies("REQ-CM-003")
    void mergedMigrationsAreNeverModifiedAndEveryMigrationIsRecorded() throws IOException {
        Map<String, String> current = currentChecksums();
        if (Boolean.getBoolean("migrations.record")) {
            recordNew(current, recordedChecksums());
        }
        Map<String, String> recorded = recordedChecksums();

        assertThat(current).as("migrations in %s", MIGRATIONS).isNotEmpty();
        recorded.forEach((file, checksum) -> assertThat(current.get(file))
                .as(
                        "%s was merged with SHA-256 %s; never modify or remove it, add a new migration instead",
                        file, checksum)
                .isEqualTo(checksum));
        assertThat(current.keySet())
                .as("every migration is recorded in %s; record new ones with -Dmigrations.record=true", MANIFEST)
                .isSubsetOf(recorded.keySet());
    }

    private static Map<String, String> currentChecksums() throws IOException {
        Map<String, String> checksums = new TreeMap<>(BY_VERSION);
        List<Path> files;
        try (Stream<Path> listing = Files.list(MIGRATIONS)) {
            files = listing.toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String content = Files.readString(file, StandardCharsets.UTF_8);
            checksums.put(name, sha256(normalized(content)));
        }
        return checksums;
    }

    private static Map<String, String> recordedChecksums() throws IOException {
        Map<String, String> checksums = new TreeMap<>(BY_VERSION);
        if (!Files.exists(MANIFEST)) {
            return checksums;
        }
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.strip().split("\\s+");
            assertThat(fields).as("manifest line '%s'", line).hasSize(2);
            assertThat(checksums.put(fields[0], fields[1]))
                    .as("%s is recorded once", fields[0])
                    .isNull();
        }
        return checksums;
    }

    private static void recordNew(Map<String, String> current, Map<String, String> recorded) throws IOException {
        StringBuilder lines = new StringBuilder();
        if (Files.exists(MANIFEST)
                && !Files.readString(MANIFEST, StandardCharsets.UTF_8).endsWith("\n")) {
            lines.append('\n');
        }
        current.forEach((file, checksum) -> {
            if (!recorded.containsKey(file)) {
                lines.append(file).append(' ').append(checksum).append('\n');
            }
        });
        Files.writeString(
                MANIFEST, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Content as Git stores it: no byte order mark, LF line endings, no trailing whitespace. */
    private static String normalized(String content) {
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        return text.replace("\r\n", "\n").stripTrailing();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available in every JDK", e);
        }
    }

    private static int version(String file) {
        Matcher matcher = VERSIONED.matcher(file);
        assertThat(matcher.matches())
                .as("%s follows the Flyway pattern V<version>__<description>.sql", file)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
