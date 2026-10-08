package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Engine mode is a single-workspace installation whose workspace still has parameters: they are
 * written through the same routes as standalone, secured values sent blank keep their secret, and
 * any other workspace change is refused.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineWorkspaceParametersTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";

  @Autowired private WebApplicationContext context;
  @Autowired private WorkspaceRepository workspaceRepository;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private MockMvc mockMvc;
  private String createdWorkspaceId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    if (workspaceRepository.findByNameIgnoreCase(SYSTEM).isEmpty()) {
      WorkspaceEntity workspace = new WorkspaceEntity();
      workspace.setName(SYSTEM);
      createdWorkspaceId = workspaceRepository.save(workspace).getId();
    }
    // Security is off in engine mode by default; the services still read the caller off the
    // SecurityContext, so a global identity stands in for the synthetic admin.
    Token token = new Token(AuthScope.global);
    token.setPrincipal("engine-workspace-parameters-test");
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(token.getPrincipal(), null);
    authentication.setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void tearDown() {
    WorkspaceEntity workspace = workspaceRepository.findByNameIgnoreCase(SYSTEM).orElseThrow();
    workspace.getParameters().removeIf(p -> p.getName().endsWith(tag));
    workspaceRepository.save(workspace);
    if (createdWorkspaceId != null) {
      workspaceRepository.deleteById(createdWorkspaceId);
    }
    SecurityContextHolder.clearContext();
  }

  private String param(String name, String type, String value) {
    return "{\"parameters\":[{\"name\":\""
        + name
        + "\",\"type\":\""
        + type
        + "\",\"value\":"
        + (value == null ? "null" : "\"" + value + "\"")
        + "}]}";
  }

  private AbstractParam stored(String name) {
    return workspaceRepository.findByNameIgnoreCase(SYSTEM).orElseThrow().getParameters().stream()
        .filter(p -> p.getName().equals(name))
        .findFirst()
        .orElse(null);
  }

  @Test
  void aParameterIsWrittenAndReadBack() throws Exception {
    String name = "region-" + tag;

    mockMvc
        .perform(
            patch("/api/v2/workspace/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content(param(name, "text", "eu-west")))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v2/workspace/system"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(name)));
    assertThat(stored(name).getValue()).isEqualTo("eu-west");
  }

  @Test
  void aSecuredValueSentBlankKeepsItsSecret() throws Exception {
    String name = "apikey-" + tag;
    mockMvc
        .perform(
            patch("/api/v2/workspace/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content(param(name, "password", "s3cret")))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch("/api/v2/workspace/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content(param(name, "password", null)))
        .andExpect(status().isOk());

    assertThat(stored(name).getValue()).isNotNull().isNotEqualTo("");
  }

  @Test
  void anyOtherWorkspaceChangeIsRefused() throws Exception {
    mockMvc
        .perform(
            patch("/api/v2/workspace/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"labels\":{\"team\":\"" + tag + "\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("TEAM_INVALID_REQ")));
  }

  @Test
  void aParameterIsDeletedAndAMissingOneIsReported() throws Exception {
    String name = "temp-" + tag;
    mockMvc
        .perform(
            patch("/api/v2/workspace/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content(param(name, "text", "x")))
        .andExpect(status().isOk());

    mockMvc.perform(delete("/api/v2/workspace/system/parameters/" + name)).andExpect(status().isOk());
    assertThat(stored(name)).isNull();

    mockMvc
        .perform(delete("/api/v2/workspace/system/parameters/" + name))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("PARAMS_INVALID_REFERENCE")));
  }
}
