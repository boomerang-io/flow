package io.boomerang.workflow;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The task-ai 2.2.0 catalogue entries the seed carries load over {@code GET /api/v2/task/{name}}:
 * the new AI Coding Agent at its first version on its own image, and AI Agent at revision 3, each
 * with the workspace tools as a multiselect of four tools and none chosen by default.
 */
class SeededAiTasksRouteTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE_TOOLS = "$.spec.params[?(@.name == 'workspaceTools')]";

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String bearer;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    try {
      TokenCreateRequest request = new TokenCreateRequest();
      request.setType(AuthScope.global);
      request.setName("seeded-ai-tasks-" + UUID.randomUUID());
      request.setPermissions(List.of("**/**"));
      bearer = "Bearer " + tokenService.create(request).getToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void theCodingAgentLoadsAtItsFirstVersionWithTheWorkspaceTools() throws Exception {
    mockMvc
        .perform(get("/api/v2/task/ai-coding-agent").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("ai-coding-agent"))
        .andExpect(jsonPath("$.type").value("ai"))
        .andExpect(jsonPath("$.displayName").value("AI Coding Agent"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.spec.image").value("boomerangio/task-ai-coding:2.2.0"))
        .andExpect(jsonPath("$.spec.command").value(contains("agent")))
        .andExpect(jsonPath("$.spec.params.length()").value(33))
        .andExpect(jsonPath(WORKSPACE_TOOLS + ".type").value(contains("multiselect")))
        .andExpect(
            jsonPath(WORKSPACE_TOOLS + ".options[*].key")
                .value(contains("read", "edit", "write", "bash")))
        .andExpect(jsonPath(WORKSPACE_TOOLS + ".default").value(contains(empty())));
  }

  @Test
  void theAgentsLatestVersionIsRevisionThreeWithTheWorkspaceTools() throws Exception {
    mockMvc
        .perform(get("/api/v2/task/ai-agent").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.spec.image").value("boomerangio/task-ai:2.2.0"))
        .andExpect(jsonPath("$.spec.command").value(contains("agent")))
        .andExpect(jsonPath(WORKSPACE_TOOLS + ".type").value(contains("multiselect")))
        .andExpect(
            jsonPath(WORKSPACE_TOOLS + ".options[*].key")
                .value(contains("read", "edit", "write", "bash")));
  }
}
