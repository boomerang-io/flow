package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.sdk.Dispatcher;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Only the engine, presenting the bearer this dispatcher registered, reads the log route; every
 * other route is left alone.
 */
class LogAuthFilterTest {

  private final Dispatcher dispatcher = mock(Dispatcher.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<Dispatcher> provider = mock(ObjectProvider.class);

  private final LogAuthFilter filter = new LogAuthFilter(provider);

  private MockHttpServletResponse call(String path, String authorization) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void theRegisteredTokenIsAccepted() throws Exception {
    when(provider.getIfAvailable()).thenReturn(dispatcher);
    when(dispatcher.logsToken()).thenReturn("secret-1");

    assertThat(call("/api/v1/logs/stream", "Bearer secret-1").getStatus()).isEqualTo(200);
  }

  @Test
  void anyOtherTokenOrNoneIsRefused() throws Exception {
    when(provider.getIfAvailable()).thenReturn(dispatcher);
    when(dispatcher.logsToken()).thenReturn("secret-1");

    assertThat(call("/api/v1/logs/stream", "Bearer secret-2").getStatus()).isEqualTo(401);
    assertThat(call("/api/v1/logs", null).getStatus()).isEqualTo(401);
    assertThat(call("/api/v1/logs/stream", "Basic abc").getStatus()).isEqualTo(401);
  }

  @Test
  void aDispatcherWithNoTokenAcceptsNothing() throws Exception {
    when(provider.getIfAvailable()).thenReturn(dispatcher);
    when(dispatcher.logsToken()).thenReturn(null);

    assertThat(call("/api/v1/logs/stream", "Bearer anything").getStatus()).isEqualTo(401);
  }

  @Test
  void otherRoutesAreNotFiltered() throws Exception {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThat(call("/health", null).getStatus()).isEqualTo(200);
    assertThat(call("/api/v1/logstash", null).getStatus()).isEqualTo(200);
  }
}
