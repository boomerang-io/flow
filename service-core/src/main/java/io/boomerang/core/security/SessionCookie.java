package io.boomerang.core.security;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The single definition of the session cookie's name and attributes - minted by {@code POST
 * /api/v2/auth/exchange}, cleared by {@code POST /api/v2/auth/logout}, read by {@link
 * AuthenticationFilter}. httpOnly, {@code Secure}, {@code SameSite=Lax} (follows ARCHIE's model,
 * specifications/authentication.md §1); carries only the opaque {@code bfs_<uuid>} value -
 * permissions are never embedded, they stay server-side on the persisted {@code TokenEntity} that
 * value hashes to, which is what makes this structurally immune to the 5KB cookie overflow the
 * embedded-permissions reference implementation hit.
 */
public final class SessionCookie {

  public static final String NAME = "flow_session";

  private SessionCookie() {}

  /** Builds the Set-Cookie value carrying {@code rawToken}, valid for {@code maxAge}. */
  public static ResponseCookie mint(String rawToken, Duration maxAge) {
    return ResponseCookie.from(NAME, rawToken)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }

  /** Builds the Set-Cookie value that clears the session cookie (logout). */
  public static ResponseCookie clear() {
    return ResponseCookie.from(NAME, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build();
  }
}
