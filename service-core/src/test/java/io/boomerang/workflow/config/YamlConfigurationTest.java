package io.boomerang.workflow.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Reproduces the real {@code TaskControllerV2}/{@code WorkspaceTaskControllerV2} mapping shape -
 * a JSON-returning method and a {@code produces = "application/x-yaml"} sibling mapped on the
 * SAME path (see e.g. {@code TaskControllerV2#get}/{@code #getYAML}) - booted through real
 * {@code @EnableWebMvc} wiring with {@link YamlConfiguration} as the only extra {@code
 * WebMvcConfigurer}, so the test exercises the actual Spring MVC content-negotiation/handler-
 * mapping machinery, not a hand-rolled approximation of it.
 *
 * <p>Without {@link YamlConfiguration#configureContentNegotiation}, an absent {@code Accept}
 * header resolves to the wildcard media type, both mappings match ambiguously, and Spring's
 * {@code ProducesRequestCondition} ranks the handler with the narrower/explicit {@code produces}
 * as the better match - so the YAML method would win even though YAML was never requested.
 */
class YamlConfigurationTest {

  @RestController
  static class DemoController {
    @GetMapping("/thing")
    public Map<String, String> getJson() {
      return Map.of("kind", "json");
    }

    @GetMapping(value = "/thing", produces = "application/x-yaml")
    public Map<String, String> getYaml() {
      return Map.of("kind", "yaml");
    }
  }

  @Configuration
  @EnableWebMvc
  static class TestConfig {
    @Bean
    YamlConfiguration yamlConfiguration() {
      return new YamlConfiguration();
    }

    @Bean
    DemoController demoController() {
      return new DemoController();
    }
  }

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
    context.register(TestConfig.class);
    context.setServletContext(new MockServletContext());
    context.refresh();

    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  @DisplayName("No Accept header resolves to the JSON handler, not the YAML sibling")
  void noAcceptHeaderYieldsJson() throws Exception {
    mockMvc
        .perform(get("/thing"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string(containsString("\"json\"")));
  }

  @Test
  @DisplayName("An explicit YAML Accept header still resolves to the YAML-capable handler")
  void explicitYamlAcceptYieldsYaml() throws Exception {
    mockMvc
        .perform(get("/thing").accept("application/x-yaml"))
        .andExpect(status().isOk())
        .andExpect(
            content().contentTypeCompatibleWith(MediaType.parseMediaType("application/x-yaml")))
        .andExpect(content().string(containsString("yaml")));
  }
}
