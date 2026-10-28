package io.boomerang.integrations;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * The state an integration's install link carries: who asked, and for which workspace, signed and
 * short-lived, so the provider's callback never trusts what the browser sends back. Each
 * integration signs with a key derived from its own server-held secret, so one integration's state
 * is never accepted by another's callback.
 */
public abstract class InstallStateUtils {

  private static final Duration STATE_TTL = Duration.ofMinutes(10);

  /** The claims recovered from a verified state. */
  public record InstallState(String workspace, String userId) {}

  private InstallStateUtils() {}

  /** Return a state for this workspace and user, signed with a key derived from {@code secret}. */
  public static String sign(String workspace, String userId, String secret) {
    Instant now = Instant.now();
    return Jwts.builder()
        .claim("workspace", workspace)
        .claim("userId", userId)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(STATE_TTL)))
        .signWith(key(secret), Jwts.SIG.HS256)
        .compact();
  }

  /**
   * Return the claims of a state signed with a key derived from {@code secret}; a state that is
   * missing, forged, expired or without both claims is {@code INTEGRATION_INVALID_STATE}.
   */
  public static InstallState verify(String state, String secret) {
    try {
      Claims claims = Jwts.parser().verifyWith(key(secret)).build().parseSignedClaims(state).getPayload();
      String workspace = claims.get("workspace", String.class);
      String userId = claims.get("userId", String.class);
      if (workspace == null || workspace.isBlank() || userId == null || userId.isBlank()) {
        throw new BoomerangException(BoomerangError.INTEGRATION_INVALID_STATE);
      }
      return new InstallState(workspace, userId);
    } catch (JwtException | IllegalArgumentException ex) {
      throw new BoomerangException(ex, BoomerangError.INTEGRATION_INVALID_STATE);
    }
  }

  // SHA-256 normalises the secret to a fixed-length HMAC key rather than using its bytes directly.
  private static SecretKey key(String secret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Keys.hmacShaKeyFor(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
