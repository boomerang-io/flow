package io.boomerang.workspace;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The webapp starts from {@code GET /api/v2/profile}, so engine mode answers it: the security-off
 * caller is the admin, and its one workspace is the system workspace.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineProfileReadTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private RoleRepository roleRepository;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void seedSystemWorkspaceAndAdminRole() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    if (workspaceRepository.findByNameIgnoreCase("system").isEmpty()) {
      WorkspaceEntity entity = new WorkspaceEntity();
      entity.setName("system");
      entity.setDisplayName("System");
      workspaceRepository.save(entity);
    }
    if (roleRepository.findByTypeAndName("global", "admin") == null) {
      RoleEntity admin = new RoleEntity();
      admin.setType(PermissionScope.global);
      admin.setName("admin");
      admin.setPermissions(List.of("**/**"));
      roleRepository.save(admin);
    }
  }

  @Test
  void theProfileIsTheAdminWithOnlyTheSystemWorkspace() throws Exception {
    mockMvc
        .perform(get("/api/v2/profile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("admin"))
        .andExpect(jsonPath("$.teams", hasSize(1)))
        .andExpect(jsonPath("$.teams[0].name").value("system"));
  }

  @Test
  void theProfileCannotBeUpdated() throws Exception {
    mockMvc
        .perform(
patch("/api/v2/profile").contentType("application/json").content("{}"))
        .andExpect(status().is4xxClientError());
  }
}
