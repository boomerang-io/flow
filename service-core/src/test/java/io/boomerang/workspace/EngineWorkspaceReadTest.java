package io.boomerang.workspace;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * In engine mode "system" is the {@code {workspace}} path value for every workspace-scoped route,
 * so the workspace resource itself resolves: {@code GET /api/v2/workspace/system} answers from the
 * workspaces collection even though WorkspaceControllerV2 and its writes are standalone-only. Any
 * other name is refused by the same rule that guards the scoped routes.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineWorkspaceReadTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private EngineWorkspaceService engineWorkspaceService;

  private MockMvc mockMvc;

  @BeforeEach
  void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void theSystemWorkspaceResolves() {
    Workspace workspace = engineWorkspaceService.get("system");

    assertEquals("system", workspace.getName());
  }

  @Test
  void anUnknownWorkspaceIsNotFound() {
    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> engineWorkspaceService.get("other"));

    assertEquals("TEAM_INVALID_REF", ex.getReason());
    assertEquals(404, ex.getStatus().value());
  }

  @Test
  void theQueryReturnsOnlyTheSystemWorkspace() {
    assertEquals(1, engineWorkspaceService.query().getTotalElements());
    assertEquals("system", engineWorkspaceService.query().getContent().get(0).getName());
  }

  @Test
  void theRouteServesTheSystemWorkspace() throws Exception {
    mockMvc
        .perform(get("/api/v2/workspace/system"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("system")));
  }

  @Test
  void theRouteRefusesAnyOtherWorkspace() throws Exception {
    mockMvc
        .perform(get("/api/v2/workspace/other"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("TEAM_INVALID_REF")));
  }
}
