package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The dispatcher contract checked in at {@code contracts/dispatcher-v1.yaml} is exactly what the
 * engine serves for its dispatcher routes, so a change to a route or a wire model cannot ship
 * without the contract changing with it. Run with {@code -Dcontract.update=true} to rewrite the
 * file after a deliberate change, then review the diff.
 */
class DispatcherContractTest extends AbstractEngineIntegrationTest {

  // Surefire runs each module from its own directory; the contract lives at the repository root.
  private static final Path CONTRACT = Path.of("..", "contracts", "dispatcher-v1.yaml");

  private static final YAMLMapper YAML = YAMLMapper.builder().build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  // The served document, less the server URL, which carries this run's random port.
  private JsonNode served() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/docs/spec/" + DispatcherContractConfiguration.GROUP))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ObjectNode document = (ObjectNode) JSON.readTree(body);
    document.remove("servers");
    return document;
  }

  @Test
  void theCheckedInContractMatchesWhatTheEngineServes() throws Exception {
    JsonNode served = served();
    if (Boolean.getBoolean("contract.update")) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, YAML.writeValueAsString(served));
    }

    assertThat(Files.exists(CONTRACT))
        .as("contracts/dispatcher-v1.yaml exists - generate it with -Dcontract.update=true")
        .isTrue();
    assertThat(YAML.readTree(Files.readString(CONTRACT)))
        .as("contracts/dispatcher-v1.yaml matches the served dispatcher routes")
        .isEqualTo(served);
  }

  @Test
  void theContractCoversOnlyTheDispatcherRoutes() throws Exception {
    served()
        .get("paths")
        .propertyNames()
        .forEach(path -> assertThat(path).startsWith("/api/v1/dispatcher"));
  }
}
