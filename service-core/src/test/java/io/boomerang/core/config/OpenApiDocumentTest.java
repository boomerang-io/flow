package io.boomerang.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole-API OpenAPI document is served, and covers what it is meant to: the public API and the
 * dispatcher wire, not the routes only the webapp's server calls.
 */
class OpenApiDocumentTest extends AbstractEngineIntegrationTest {

  private static final String DOCUMENT = "/api/docs/spec";
  private static final String RUN_QUERY = "/api/v2/workspace/{workspace}/workflowrun/query";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  private JsonNode document() throws Exception {
    String body =
        mockMvc
            .perform(get(DOCUMENT))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonMapper.builder().build().readTree(body);
  }

  @Test
  void theDocumentCoversThePublicApiAndTheDispatcherWireButNotTheInternalRoutes() throws Exception {
    List<String> paths = new ArrayList<>();
    document().get("paths").propertyNames().forEach(paths::add);

    assertThat(paths).anyMatch(path -> path.startsWith("/api/v2/"));
    assertThat(paths).anyMatch(path -> path.startsWith("/dispatcher/v1/"));
    assertThat(paths).noneMatch(path -> path.startsWith("/internal/"));
  }

  @Test
  void anOptionalParametersDefaultIsDocumentedAsItsValue() throws Exception {
    // `page` is an Optional<Integer> with a default: left wrapped, it fails the whole document.
    JsonNode page = null;
    for (JsonNode parameter : document().get("paths").get(RUN_QUERY).get("get").get("parameters")) {
      if ("page".equals(parameter.get("name").asString())) {
        page = parameter;
      }
    }

    assertThat(page).as("the run query documents its page parameter").isNotNull();
    assertThat(page.get("schema").get("default").asInt()).isZero();
  }
}
