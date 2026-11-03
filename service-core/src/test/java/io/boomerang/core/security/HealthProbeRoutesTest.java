package io.boomerang.core.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The health routes answer without a session on a secured install: the one Compose probes and the
 * liveness and readiness groups Kubernetes probes under it.
 */
class HealthProbeRoutesTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private FilterChainProxy springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  // MockMvc leaves the servlet path empty unless told; the filter reads it, as a servlet sets it.
  @Test
  void theHealthRouteAndItsProbeGroupsNeedNoSession() throws Exception {
    for (String path : new String[] {"/health", "/health/liveness", "/health/readiness"}) {
      mockMvc.perform(get(path).servletPath(path)).andExpect(status().isOk());
    }
  }

  @Test
  void theApiStillNeedsASession() throws Exception {
    mockMvc
        .perform(get("/api/v2/features").servletPath("/api/v2/features"))
        .andExpect(status().isUnauthorized());
  }
}
