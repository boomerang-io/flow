package io.boomerang.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;

/**
 * The response of {@code GET /api/v2/auth/config} - the pre-auth bootstrap contract the webapp
 * reads BEFORE it holds any session, to learn how to sign in. {@code issuer} and {@code clientId}
 * are present only when {@code mode} is {@code oidc}; nothing else from the auth settings is ever
 * exposed through this shape.
 */
@Data
@JsonInclude(Include.NON_NULL)
public class AuthConfig {

  /** Serialised by name, deliberately lowercase: {@code none}, {@code proxy}, {@code oidc}. */
  public enum AuthMode {
    none,
    proxy,
    oidc
  }

  private final AuthMode mode;
  private final String issuer;
  private final String clientId;

  public static AuthConfig none() {
    return new AuthConfig(AuthMode.none, null, null);
  }

  public static AuthConfig proxy() {
    return new AuthConfig(AuthMode.proxy, null, null);
  }

  public static AuthConfig oidc(String issuer, String clientId) {
    return new AuthConfig(AuthMode.oidc, issuer, clientId);
  }
}
