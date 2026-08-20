package io.boomerang.core.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.SettingsService;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies id_tokens for the direct OIDC login path of {@code POST /api/v2/auth/exchange}
 * (specifications/authentication.md §1/§5): fetches the configured issuer's JWKS (via standard
 * OIDC discovery), verifies the JWS signature, and checks {@code iss}, {@code aud}, {@code exp}
 * and {@code nonce} - the reference implementation this design is modelled on base64-decodes and
 * trusts transport instead; this endpoint is reachable directly by a browser, so it earns trust
 * cryptographically.
 *
 * <p>Single configured issuer (not an allowlist) - {@code settings.auth.oidc.issuer} /
 * {@code settings.auth.oidc.clientId}, seeded empty (see {@code
 * io.boomerang.loader.migration._0035__AddAuthSettings}). Every trusted issuer is new attack
 * surface: anything accepted here can mint identities Flow will believe.
 */
@Service
public class OidcTokenVerifier {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String SETTINGS_KEY = "auth";
  private static final String ISSUER_CONFIG_KEY = "oidc.issuer";
  private static final String CLIENT_ID_CONFIG_KEY = "oidc.clientId";
  private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

  // Only the signature-check machinery (built from the issuer's JWKS) is cached, keyed by issuer -
  // never the claims verifier, which is built fresh per call since it is bound to that call's
  // one-time nonce (see verify()). A shared, mutated-in-place claims verifier would let concurrent
  // logins race each other's nonce.
  private volatile String cachedIssuer;
  private volatile JWKSource<SecurityContext> cachedKeySource;
  private final Object cacheLock = new Object();

  private final SettingsService settingsService;
  private final RestTemplate restTemplate;

  public OidcTokenVerifier(
      SettingsService settingsService, @Qualifier("externalRestTemplate") RestTemplate restTemplate) {
    this.settingsService = settingsService;
    this.restTemplate = restTemplate;
  }

  /**
   * Verifies {@code idToken} against the configured issuer and returns its claims on success.
   * {@code expectedNonce} is the value the frontend generated for its PKCE authorize request -
   * required and checked against the token's own {@code nonce} claim, so a token cannot be
   * replayed into an exchange it was not issued for.
   *
   * @throws BoomerangException {@code AUTH_NOT_CONFIGURED} if no issuer/clientId is configured (or
   *     the issuer's discovery/JWKS could not be resolved); {@code AUTH_TOKEN_INVALID} for any
   *     signature, claims (iss/aud/exp/nonce), or parse failure.
   */
  public JWTClaimsSet verify(String idToken, String expectedNonce) {
    if (idToken == null || idToken.isBlank()) {
      throw new BoomerangException(BoomerangError.AUTH_TOKEN_INVALID);
    }
    if (expectedNonce == null || expectedNonce.isBlank()) {
      throw new BoomerangException(BoomerangError.AUTH_TOKEN_INVALID);
    }

    String issuer = requireSetting(ISSUER_CONFIG_KEY);
    String clientId = requireSetting(CLIENT_ID_CONFIG_KEY);

    JWKSource<SecurityContext> keySource = keySourceFor(issuer);
    JWSKeySelector<SecurityContext> keySelector =
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(keySelector);
    JWTClaimsSet exactMatch =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(clientId)
            .claim("nonce", expectedNonce)
            .build();
    processor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier<>(exactMatch, Set.of("sub", "iss", "aud", "exp", "nonce")));

    try {
      return processor.process(idToken, null);
    } catch (ParseException | BadJOSEException ex) {
      throw new BoomerangException(ex, BoomerangError.AUTH_TOKEN_INVALID);
    } catch (JOSEException ex) {
      // JWKS fetch/signature-processing failure - most likely a transient issuer/network issue,
      // but surfaced identically to any other verification failure: the caller never learns
      // which check failed.
      throw new BoomerangException(ex, BoomerangError.AUTH_TOKEN_INVALID);
    }
  }

  private String requireSetting(String key) {
    try {
      String value = settingsService.getSettingConfig(SETTINGS_KEY, key).getValue();
      if (value == null || value.isBlank()) {
        throw new BoomerangException(BoomerangError.AUTH_NOT_CONFIGURED);
      }
      return value;
    } catch (IllegalArgumentException ex) {
      throw new BoomerangException(ex, BoomerangError.AUTH_NOT_CONFIGURED);
    }
  }

  private JWKSource<SecurityContext> keySourceFor(String issuer) {
    JWKSource<SecurityContext> keySource = cachedKeySource;
    if (keySource != null && issuer.equals(cachedIssuer)) {
      return keySource;
    }
    synchronized (cacheLock) {
      if (cachedKeySource != null && issuer.equals(cachedIssuer)) {
        return cachedKeySource;
      }
      String jwksUri = discoverJwksUri(issuer);
      try {
        JWKSource<SecurityContext> newKeySource =
            new RemoteJWKSet<>(new URL(jwksUri), new RestTemplateResourceRetriever());
        cachedKeySource = newKeySource;
        cachedIssuer = issuer;
        return newKeySource;
      } catch (MalformedURLException ex) {
        throw new BoomerangException(ex, BoomerangError.AUTH_NOT_CONFIGURED);
      }
    }
  }

  private String discoverJwksUri(String issuer) {
    String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> discovery =
          restTemplate.getForObject(base + DISCOVERY_PATH, Map.class);
      Object jwksUri = discovery == null ? null : discovery.get("jwks_uri");
      if (!(jwksUri instanceof String jwksUriString) || jwksUriString.isBlank()) {
        throw new BoomerangException(BoomerangError.AUTH_NOT_CONFIGURED);
      }
      return jwksUriString;
    } catch (RestClientException ex) {
      LOGGER.warn("OIDC discovery failed for issuer {}: {}", issuer, ex.getMessage());
      throw new BoomerangException(ex, BoomerangError.AUTH_NOT_CONFIGURED);
    }
  }

  /*
   * Routes the JWKS fetch through the platform's configured externalRestTemplate (proxy support,
   * timeouts) rather than nimbus's default java.net-based retriever - the custom RestConfig is a
   * product requirement for every outbound call, not just the ones already wired up.
   */
  private final class RestTemplateResourceRetriever implements ResourceRetriever {
    @Override
    public Resource retrieveResource(URL url) throws IOException {
      try {
        String body = restTemplate.getForObject(url.toURI(), String.class);
        return new Resource(body, "application/json");
      } catch (RestClientException | URISyntaxException ex) {
        throw new IOException("Unable to retrieve " + url, ex);
      }
    }
  }
}
