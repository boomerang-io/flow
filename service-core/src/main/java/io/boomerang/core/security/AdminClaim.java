package io.boomerang.core.security;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether an identity provider's token makes its holder a Flow admin: the claim named by {@code
 * flow.security.admin-claim.name} (a string, or an array of strings such as {@code roles} or {@code
 * groups}) holding any of {@code flow.security.admin-claim.values}. Unset, no token grants admin.
 *
 * <p>A token the caller can write (the unverified JWT an authenticating proxy forwards) is read
 * only when {@code flow.security.admin-claim.trust-forwarded-token} is on, because then the proxy
 * is the whole trust boundary; a signature-verified id_token is always read.
 */
@Component
public class AdminClaim {

  private final String name;
  private final Set<String> values;
  private final boolean trustForwardedToken;

  public AdminClaim(
      @Value("${flow.security.admin-claim.name:}") String name,
      @Value("${flow.security.admin-claim.values:}") List<String> values,
      @Value("${flow.security.admin-claim.trust-forwarded-token:false}") boolean trustForwardedToken) {
    this.name = name == null ? "" : name.trim();
    this.values =
        values.stream().map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toSet());
    this.trustForwardedToken = trustForwardedToken;
  }

  /** True when both a claim name and at least one admin value are set. */
  public boolean configured() {
    return !name.isEmpty() && !values.isEmpty();
  }

  /** Admin or not by a signature-verified token; null when no claim is configured. */
  public Boolean evaluateVerified(JWTClaimsSet claims) {
    return configured() ? Boolean.valueOf(grants(claims)) : null;
  }

  /** Admin or not by a proxy-forwarded token; null when unconfigured or the proxy is not trusted. */
  public Boolean evaluateForwarded(JWTClaimsSet claims) {
    return trustForwardedToken ? evaluateVerified(claims) : null;
  }

  private boolean grants(JWTClaimsSet claims) {
    Object claim = claims.getClaim(name);
    if (claim instanceof Collection<?> entries) {
      return entries.stream().anyMatch(entry -> entry != null && values.contains(entry.toString()));
    }
    return claim != null && values.contains(claim.toString());
  }
}
