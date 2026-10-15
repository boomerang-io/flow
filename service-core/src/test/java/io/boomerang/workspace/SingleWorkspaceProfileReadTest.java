package io.boomerang.workspace;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
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

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
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
