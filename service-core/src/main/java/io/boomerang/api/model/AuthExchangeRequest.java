package io.boomerang.api.model;

import lombok.Data;

/**
 * The body of {@code POST /api/v2/auth/exchange} (specifications/authentication.md §1). Both
 * fields are optional and empty is a valid, meaningful request: an empty body selects the
 * proxy-forwarded-identity path. A populated {@code idToken} selects the direct OIDC login path -
 * {@code nonce} must be the value the frontend generated for its PKCE authorize request, so it can
 * be checked against the token's own {@code nonce} claim.
 */
@Data
public class AuthExchangeRequest {

  private String idToken;
  private String nonce;
}
