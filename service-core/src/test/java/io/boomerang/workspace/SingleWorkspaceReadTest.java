package io.boomerang.workspace;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.Workspace;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * With {@code flow.workspace.single=true} "system" is the {@code {workspace}} path value for every
 * workspace-scoped route, so the workspace resource itself resolves through the standard
 * {@link WorkspaceService} and {@link WorkspaceControllerV2}. Any other name on the route is
 * refused by the same guard that protects the scoped routes.
 */
@TestPropertySource(properties = {"flow.workspace.single=true", "flow.security.enabled=false"})
class SingleWorkspaceReadTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private WorkspaceService workspaceService;

  private MockMvc mockMvc;

  @BeforeEach
  void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void theSystemWorkspaceResolves() {
    Workspace workspace = workspaceService.get("system");

    assertEquals("system", workspace.getName());
  }

  @Test
  void anUnknownWorkspaceIsNotFound() {
    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> workspaceService.get("other"));

    assertEquals("WORKSPACE_INVALID_REF", ex.getReason());
    assertEquals(404, ex.getStatus().value());
  }

  @Test
  void theQueryListsTheSystemWorkspace() {
    assertTrue(
        workspaceService
            .query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getContent()
            .stream()
            .anyMatch(workspace -> "system".equals(workspace.getName())));
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
        .andExpect(content().string(containsString("WORKSPACE_INVALID_REF")));
  }
}
