package io.boomerang.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The public API contract checked in at {@code contracts/api-v2.yaml} is exactly what the server
 * serves for its {@code /api/v2} routes, so a change to a route or a public model cannot ship
 * without the contract changing with it; {@code flow-sdk} tests its models against the same file.
 * Run with {@code -Dcontract.update=true} to rewrite the file after a deliberate change, then
 * review the diff.
 */
@Tag("smoke")
class ApiContractTest extends AbstractEngineIntegrationTest {

  // Surefire runs each module from its own directory; the contract lives at the repository root.
  private static final Path CONTRACT = Path.of("..", "contracts", "api-v2.yaml");

  private static final YAMLMapper YAML = YAMLMapper.builder().build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  // The served document, less the server URL, which carries this run's random port, with every
  // object's keys sorted: springdoc lists paths and schemas in discovery order, which is not
  // stable between runs, and a regenerated file must diff only where the API changed.
  private JsonNode served() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/docs/spec/" + OpenApiConfiguration.API_GROUP))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ObjectNode document = (ObjectNode) JSON.readTree(body);
    document.remove("servers");
    return sorted(document);
  }

  private static JsonNode sorted(JsonNode node) {
    if (node instanceof ObjectNode object) {
      TreeMap<String, JsonNode> byKey = new TreeMap<>();
      object.properties().forEach(entry -> byKey.put(entry.getKey(), sorted(entry.getValue())));
      ObjectNode result = JSON.createObjectNode();
      byKey.forEach(result::set);
      return result;
    }
    if (node instanceof ArrayNode array) {
      ArrayNode result = JSON.createArrayNode();
      array.forEach(element -> result.add(sorted(element)));
      return result;
    }
    return node;
  }

  @Test
  void theCheckedInContractMatchesWhatTheServerServes() throws Exception {
    JsonNode served = served();
    if (Boolean.getBoolean("contract.update")) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, YAML.writeValueAsString(served));
    }

    assertThat(Files.exists(CONTRACT))
        .as("contracts/api-v2.yaml exists - generate it with -Dcontract.update=true")
        .isTrue();
    assertThat(YAML.readTree(Files.readString(CONTRACT)))
        .as("contracts/api-v2.yaml matches the served public API")
        .isEqualTo(served);
  }

  @Test
  void theContractCoversOnlyThePublicApi() throws Exception {
    served().get("paths").propertyNames().forEach(path -> assertThat(path).startsWith("/api/v2/"));
  }
}
