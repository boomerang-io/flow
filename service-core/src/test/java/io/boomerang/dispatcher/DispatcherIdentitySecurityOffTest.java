package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.UnauthenticatedGlobalToken;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * With security off every other route runs as the synthetic system identity, but the dispatcher
 * wire still checks its bearer, and the dispatcher's own token is the identity its routes see.
 */
@TestPropertySource(properties = {"flow.workspace.single=true", "flow.security.enabled=false"})
class DispatcherIdentitySecurityOffTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private IdentityService identityService;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aDispatcherRouteRunsAsTheDispatchersOwnTokenNotTheSystem() throws Exception {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("dispatcher-identity-security-off");
    request.setActorKind(TokenActorKind.SERVICE);
    request.setPermissions(List.of("**/**"));
    String raw = tokenService.create(request).getToken();
    SecurityContextHolder.clearContext();
    AtomicReference<Token> seen = new AtomicReference<>();
    MockMvc capturing =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                springSecurityFilterChain,
                (servletRequest, servletResponse, chain) -> {
                  seen.set(identityService.getCurrentIdentity());
                  chain.doFilter(servletRequest, servletResponse);
                })
            .build();

    capturing
        .perform(
            post("/dispatcher/v1/workspaces/releasable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + raw)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowRunRefs\":[],\"workflowRefs\":[]}"))
        .andExpect(status().isOk());

    assertThat(seen.get()).isNotNull().isNotInstanceOf(UnauthenticatedGlobalToken.class);
    assertThat(seen.get().getId()).isEqualTo(tokenService.get(raw).getId());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
