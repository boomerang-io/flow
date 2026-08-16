package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.enums.TokenTypePrefix;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
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
 * Security contract guard for the dispatcher endpoints (T6-1). {@code /api/v1/dispatcher/**}
 * requires a real Flow token: {@code global} scope + a machine {@link TokenActorKind}. Covers:
 * missing bearer, a valid dispatcher token, a human/user-scope token (rejected — wrong scope AND
 * no {@code actorKind}), an expired token, and a bearer that isn't Flow-token-shaped at all
 * (rejected by the {@link TokenTypePrefix#isFlowToken} pre-DB gate before any Mongo lookup — see
 * that method's own unit test for the gate's shape coverage; {@link DispatcherAuthFilter}'s
 * source shows this bearer never reaches {@link TokenService}).
 *
 * <p>MockMvc is built from the real WebApplicationContext with the actual Spring Security filter
 * chain wired in, so the request genuinely traverses {@code DispatcherAuthFilter}.
 */
@TestPropertySource(properties = {"flow.queue.enabled=false"})
class DispatcherAuthTest extends AbstractEngineIntegrationTest {

  private static final String DISPATCHER_PATH = "/api/v1/dispatcher/any-agent-id/tasks";

  @Autowired private WebApplicationContext context;

  @Autowired private TokenService tokenService;

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
  void validDispatcherTokenPassesTheFilter() throws Exception {
    String raw = mintToken(AuthScope.global, TokenActorKind.SERVICE, null);

    mockMvc
        .perform(get(DISPATCHER_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
        .andExpect(status().is(not(401)));
  }

  @Test
  void humanScopeTokenIsRejected() throws Exception {
    // A user-scope token: real principal, no actorKind - the shape of a human's token, not a
    // machine's. Rejected on BOTH grounds (validateActorToken requires global scope AND a
    // non-null actorKind), but this test only needs the observable outcome.
    String raw = mintToken(AuthScope.user, null, null);

    mockMvc
        .perform(get(DISPATCHER_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.HOUR, -1);
    String raw = mintToken(AuthScope.global, TokenActorKind.SERVICE, cal.getTime());

    mockMvc
        .perform(get(DISPATCHER_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nonFlowShapedBearerIsRejected() throws Exception {
    mockMvc
        .perform(
            get(DISPATCHER_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-flow-token"))
        .andExpect(status().isUnauthorized());
  }

  /** Pure unit coverage of the pre-DB gate {@link DispatcherAuthFilter} relies on. */
  @Test
  void tokenPrefixGateRejectsNonFlowShapesBeforeAnyLookupWouldOccur() {
    assertThat(TokenTypePrefix.isFlowToken("not-a-flow-token")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken(null)).isFalse();
    assertThat(TokenTypePrefix.isFlowToken("bfg_")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken("bfg_" + "abc-123")).isTrue();
  }

  private String mintToken(AuthScope type, TokenActorKind actorKind, Date expirationDate) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(type);
    request.setName("dispatcher-auth-test");
    request.setActorKind(actorKind);
    request.setExpirationDate(expirationDate);
    if (AuthScope.user.equals(type)) {
      request.setPrincipal("human-user-1");
    } else {
      request.setPermissions(List.of("**/**"));
    }
    TokenCreateResponse response = tokenService.create(request);
    return response.getToken();
  }
}
