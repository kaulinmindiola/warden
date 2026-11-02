package io.github.kaulinmindiola.warden.shared.security;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versioned OpenAPI document, {@code docs/api/openapi.json}, matches the one springdoc
 * generates from the code (REQ-MAINT-008). After an API change the test fails; regenerate the file
 * and review the diff with:
 *
 * <pre>
 * ./mvnw verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=OpenApiDocumentIT -Dopenapi.update=true
 * </pre>
 */
class OpenApiDocumentIT extends IntegrationTest {

    static final Path DOCUMENT = Path.of("docs", "api", "openapi.json");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @Verifies("REQ-MAINT-008")
    void theVersionedDocumentMatchesTheCode() throws Exception {
        String generated = generated();

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(DOCUMENT.getParent());
            Files.writeString(DOCUMENT, generated, StandardCharsets.UTF_8);
        }

        assertThat(Files.exists(DOCUMENT))
                .as("%s exists; create it with -Dopenapi.update=true", DOCUMENT)
                .isTrue();
        assertThat(Files.readString(DOCUMENT, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("%s is up to date; regenerate it with -Dopenapi.update=true and review the diff", DOCUMENT)
                .isEqualTo(generated);
    }

    @Test
    @Verifies("REQ-MAINT-008")
    void everyEndpointIsDocumentedAndOnlyThePublicOnesNeedNoToken() throws Exception {
        JsonNode paths = JsonMapper.builder().build().readTree(generated()).get("paths");

        Set<String> documented = new TreeSet<>();
        paths.properties().forEach(path -> documented.add(path.getKey()));
        Set<String> mapped = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, ?> handler :
                handlerMapping.getHandlerMethods().entrySet()) {
            handler.getKey().getPatternValues().stream()
                    .filter(pattern -> pattern.startsWith(API + "/"))
                    .forEach(mapped::add);
        }
        assertThat(documented).containsExactlyInAnyOrderElementsOf(mapped);

        paths.properties()
                .forEach(path -> path.getValue().properties().forEach(operation -> {
                    boolean secured = operation.getValue().has("security");
                    assertThat(secured)
                            .as("%s %s requires the access token", operation.getKey(), path.getKey())
                            .isEqualTo(!SecurityConfiguration.PUBLIC_API_PATHS.contains(path.getKey()));
                }));
    }

    /** The document springdoc serves, with Unix line endings and a final newline. */
    private String generated() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context)
                .build()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String document = body.replace("\r\n", "\n");
        return document.endsWith("\n") ? document : document + "\n";
    }
}
