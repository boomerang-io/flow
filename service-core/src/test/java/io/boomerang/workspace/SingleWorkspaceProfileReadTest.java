package io.boomerang.workspace;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The webapp starts from {@code GET /api/v2/profile}, and the standard {@link ProfileControllerV2}
 * answers it on an embedded engine too: with security off the caller is the synthetic admin, whose
 * workspace list includes the {@code system} workspace.
 */
@TestPropertySource(properties = {"flow.workspace.single=true", "flow.security.enabled=false"})
class SingleWorkspaceProfileReadTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  @Test
  void theProfileIsTheAdminWithTheSystemWorkspace() throws Exception {
    mockMvc
        .perform(get("/api/v2/profile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("admin"))
        .andExpect(jsonPath("$.teams[*].name").value(hasItem("system")));
  }
}
