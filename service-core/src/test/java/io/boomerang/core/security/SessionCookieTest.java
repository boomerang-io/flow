package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie's attributes are ruled, not incidental (specifications/authentication.md
 * §1): httpOnly + Secure + SameSite=Lax, carrying only the opaque value - never permissions.
 */
class SessionCookieTest {

  @Test
  void mintedCookieIsHttpOnlyAndSecure() {
    ResponseCookie cookie = SessionCookie.mint("bfs_raw-value", Duration.ofHours(8));

    assertThat(cookie.getName()).isEqualTo(SessionCookie.NAME);
    assertThat(cookie.getValue()).isEqualTo("bfs_raw-value");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofHours(8));
  }

  @Test
  void clearedCookieIsHttpOnlyAndSecureWithZeroMaxAge() {
    ResponseCookie cookie = SessionCookie.clear();

    assertThat(cookie.getName()).isEqualTo(SessionCookie.NAME);
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
  }
}
