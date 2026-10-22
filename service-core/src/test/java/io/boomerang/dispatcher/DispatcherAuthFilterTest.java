package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.security.UnauthenticatedGlobalToken;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** The filter alone, for the local-development pass-through no test context runs with. */
class DispatcherAuthFilterTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void withDispatcherAuthenticationOffTheRequestActsAsTheSystem() throws Exception {
    AtomicReference<Object> seen = new AtomicReference<>();

    new DispatcherAuthFilter(null, false)
        .doFilter(
            new MockHttpServletRequest("GET", "/dispatcher/v1/any-agent-id/tasks"),
            new MockHttpServletResponse(),
            (request, response) ->
                seen.set(SecurityContextHolder.getContext().getAuthentication().getDetails()));

    assertThat(seen.get()).isInstanceOf(UnauthenticatedGlobalToken.class);
  }
}
