package io.boomerang.core.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Reproduces, and pins the fix for, the streaming-log regression: a {@link
 * StreamingResponseBody} endpoint (real example: {@code GET /api/v2/taskrun/{id}/log}) completes
 * its write fine, but the framework's own ASYNC re-dispatch was denied by {@link
 * SecurityConfiguration}'s {@code authorizeHttpRequests} chain - {@link AuthenticationFilter}
 * only ever populates the {@code SecurityContext} on the initial REQUEST dispatch (it is a {@code
 * OncePerRequestFilter} and takes the framework default of skipping ASYNC dispatch), so
 * Spring Security's {@code AuthorizationFilter} saw no {@code Authentication} on the re-dispatch
 * and denied a response that was already committed.
 *
 * <p>Exercises a minimal test-only {@link StreamingResponseBody} controller through the REAL
 * {@code springSecurityFilterChain} (same established pattern as {@code DispatcherAuthTest}),
 * because the regression is specifically about dispatcher-type handling in the security filter
 * chain - a {@code standaloneSetup} MockMvc (no security filters) cannot exercise it.
 */
@Import(StreamingResponseAsyncDispatchTest.TestStreamingConfig.class)
class StreamingResponseAsyncDispatchTest extends AbstractEngineIntegrationTest {

  private static final String STREAM_PATH = "/test-stream/log";
  private static final String STREAM_BODY = "hello from the stream";

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

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void asyncDispatchOfAStreamingResponseCompletesWithoutBeingDenied() throws Exception {
    String bearer = mintGlobalToken();

    MvcResult mvcResult =
        mockMvc
            .perform(get(STREAM_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(content().string(STREAM_BODY));
  }

  private String mintGlobalToken() {
    seedGlobalAdminIdentity();
    try {
      TokenCreateRequest request = new TokenCreateRequest();
      request.setType(AuthScope.global);
      request.setName("streaming-async-dispatch-test");
      request.setPermissions(List.of("**/**"));
      TokenCreateResponse response = tokenService.create(request);
      return response.getToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void seedGlobalAdminIdentity() {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("streaming-async-dispatch-test-admin");
    admin.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    ((UsernamePasswordAuthenticationToken) authentication).setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  static class TestStreamingConfig {

    @Bean
    TestStreamingController testStreamingController() {
      return new TestStreamingController();
    }
  }

  @RestController
  static class TestStreamingController {

    @GetMapping(STREAM_PATH)
    @AuthCriteria(
        action = PermissionAction.READ,
        resource = PermissionResource.SYSTEM,
        assignableScopes = {AuthScope.global})
    StreamingResponseBody streamLog() {
      return outputStream -> outputStream.write(STREAM_BODY.getBytes());
    }
  }
}
