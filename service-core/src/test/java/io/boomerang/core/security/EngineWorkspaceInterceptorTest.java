package io.boomerang.core.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Behavioural guard for AM-10: in engine mode, {@link EngineWorkspaceInterceptor} must reject any
 * workspace-scoped request whose {@code {team}} path variable is not {@code system}, on both the
 * {@code /team/} and {@code /workspace/} DD-01 aliases, while letting {@code system} straight
 * through to the real controller/service.
 *
 * <p>MockMvc is built from the real {@link WebApplicationContext} (same established pattern as
 * {@code DispatcherAuthTest}) so the request genuinely traverses Spring MVC's
 * HandlerMapping/HandlerInterceptor chain - the only way to prove the interceptor is actually
 * registered with the right path patterns in engine mode and reads {@code
 * URI_TEMPLATE_VARIABLES_ATTRIBUTE} at the right point, rather than just that its {@code
 * preHandle} logic is correct in isolation.
 *
 * <p>The "pass" cases target a lookup for a workflow that doesn't exist: that reaches
 * {@code WorkspaceWorkflowService.get}, which throws {@code WORKFLOW_INVALID_REF} - proving the
 * request cleared the interceptor and hit real business logic, without depending on a populated
 * database or on {@code Page}/{@code WorkflowResponsePage} response-body serialization (which the
 * default content-negotiation setup in this test context doesn't cleanly support). The response
 * body is asserted with a plain substring match on the {@code reason} value so the assertion holds
 * regardless of which message converter content negotiation happens to select.
 *
 * <p>Security is disabled by default in engine mode (see {@code FlowSecurityProperties}), so no
 * auth filter setup is needed to reach the interceptor - but the underlying service still reads
 * the current principal off the SecurityContext ({@code RelationshipService.filter}) regardless of
 * whether authentication is enforced, so a minimal {@code global}-scope identity is seeded for the
 * pass-through requests.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineWorkspaceInterceptorTest extends AbstractEngineIntegrationTest {

  private static final String MISSING_WORKFLOW = "does-not-exist-xyz";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void seedGlobalIdentity() {
    Token token = new Token(AuthScope.global);
    token.setPrincipal("engine-workspace-interceptor-test");
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(token.getPrincipal(), null);
    ((UsernamePasswordAuthenticationToken) authentication).setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void systemWorkspacePassesOnTeamAlias() throws Exception {
    seedGlobalIdentity();
    mockMvc
        .perform(get("/api/v2/team/system/workflow/" + MISSING_WORKFLOW))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("WORKFLOW_INVALID_REFERENCE")));
  }

  @Test
  void systemWorkspacePassesOnWorkspaceAlias() throws Exception {
    seedGlobalIdentity();
    mockMvc
        .perform(get("/api/v2/workspace/system/workflow/" + MISSING_WORKFLOW))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("WORKFLOW_INVALID_REFERENCE")));
  }

  @Test
  void nonSystemWorkspaceIsRejectedOnTeamAlias() throws Exception {
    mockMvc
        .perform(get("/api/v2/team/not-system/workflow/" + MISSING_WORKFLOW))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("TEAM_INVALID_REF")));
  }

  @Test
  void nonSystemWorkspaceIsRejectedOnWorkspaceAlias() throws Exception {
    mockMvc
        .perform(get("/api/v2/workspace/not-system/workflow/" + MISSING_WORKFLOW))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("TEAM_INVALID_REF")));
  }
}
