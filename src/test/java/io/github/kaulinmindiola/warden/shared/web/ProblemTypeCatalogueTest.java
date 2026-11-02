package io.github.kaulinmindiola.warden.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/api/problem-types.md}, the catalogue API clients read, in step with {@link
 * ProblemType}: every type is documented once, in declaration order, with its status and title.
 */
class ProblemTypeCatalogueTest {

    private static final Path CATALOGUE = Path.of("docs", "api", "problem-types.md");

    @Test
    @Verifies("REQ-INT-005")
    void theDocumentedCatalogueListsExactlyTheProblemTypesOfTheCode() throws IOException {
        List<String> declared = Arrays.stream(ProblemType.values())
                .map(type -> row(type.slug(), String.valueOf(type.status().value()), type.title()))
                .toList();

        assertThat(documentedRows()).containsExactlyElementsOf(declared);
    }

    private static List<String> documentedRows() throws IOException {
        List<String> lines = Files.readAllLines(CATALOGUE, StandardCharsets.UTF_8);
        int heading = lines.indexOf("## Catalogue");
        assertThat(heading).as("the '## Catalogue' heading of %s", CATALOGUE).isNotNegative();
        return lines.stream()
                .skip(heading + 1L)
                .takeWhile(line -> !line.startsWith("## "))
                .filter(line -> line.startsWith("| `"))
                .map(line -> line.split("\\|"))
                .map(cells -> row(cells[1].strip().replace("`", ""), cells[2].strip(), cells[3].strip()))
                .toList();
    }

    private static String row(String slug, String status, String title) {
        return slug + " | " + status + " | " + title;
    }
}
