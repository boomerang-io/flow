package io.boomerang.engine;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security contract guard for the dispatcher endpoints (A3). With {@code flow.dispatcher.token}
 * configured, a request to {@code /api/v1/dispatcher/**} without a matching bearer token must be
 * rejected 401; with the correct token it must pass the {@code DispatcherAuthFilter} (queue
 * disabled here so the handler returns 204 rather than exercising the Mongo path). The blank-token
 * permit path is covered implicitly by every other test in the suite (no token set → no
 * enforcement).
 *
 * <p>MockMvc is built from the real WebApplicationContext with the actual Spring Security filter
 * chain wired in, so the request genuinely traverses {@code DispatcherAuthFilter}.
 */
@TestPropertySource(
    properties = {"flow.dispatcher.token=test-secret-token", "flow.queue.enabled=false"})
class DispatcherAuthTest extends AbstractEngineIntegrationTest {

  private static final String TOKEN = "test-secret-token";
  private static final String DISPATCHER_PATH = "/api/v1/dispatcher/any-agent-id/tasks";

  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  @Test
  void missingBearerTokenIsRejected() throws Exception {
    mockMvc.perform(get(DISPATCHER_PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  void correctBearerTokenPassesTheFilter() throws Exception {
    mockMvc
        .perform(get(DISPATCHER_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andExpect(status().is(not(401)));
  }
}
