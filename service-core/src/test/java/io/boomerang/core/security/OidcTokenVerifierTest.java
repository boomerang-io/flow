package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.SettingsService;
import io.boomerang.core.model.SettingConfig;
import java.net.URI;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

/**
 * Covers the OIDC direct-login path's cryptographic verification (specifications/authentication.md
 * §1/§5): a real RSA-signed JWT verified via a mocked JWKS/discovery response, so these exercise
 * the actual nimbus-jose-jwt signature/claims verification pipeline rather than a stub.
 */
@ExtendWith(MockitoExtension.class)
class OidcTokenVerifierTest {

  private static final String ISSUER = "https://idpzero.example.test";
  private static final String CLIENT_ID = "flow-web";
  private static final String JWKS_URI = "https://idpzero.example.test/jwks.json";
  private static final String DISCOVERY_URL = ISSUER + "/.well-known/openid-configuration";
  private static final String NONCE = "test-nonce-1";

  @Mock private SettingsService settingsService;
  @Mock private RestTemplate restTemplate;

  private OidcTokenVerifier verifier;
  private RSAKey rsaKey;

  @BeforeEach
  void setUp() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    verifier = new OidcTokenVerifier(settingsService, restTemplate);

    lenient()
        .when(settingsService.getSettingConfig("auth", "oidc.issuer"))
        .thenReturn(configOf(ISSUER));
    lenient()
        .when(settingsService.getSettingConfig("auth", "oidc.clientId"))
        .thenReturn(configOf(CLIENT_ID));
    lenient()
        .when(restTemplate.getForObject(eq(DISCOVERY_URL), eq(Map.class)))
        .thenReturn(Map.of("jwks_uri", JWKS_URI));
    lenient()
        .when(restTemplate.getForObject(eq(URI.create(JWKS_URI)), eq(String.class)))
        .thenReturn(new JWKSet(rsaKey.toPublicJWK()).toString());
  }

  @Test
  void acceptsAValidToken() throws Exception {
    String token = signedToken(rsaKey, ISSUER, CLIENT_ID, NONCE, futureDate());

    JWTClaimsSet claims = verifier.verify(token, NONCE);

    assertThat(claims.getSubject()).isEqualTo("user-1");
    assertThat(claims.getStringClaim("email")).isEqualTo("person@example.test");
  }

  @Test
  void rejectsAnUnsignedToken() throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("user-1")
            .issuer(ISSUER)
            .audience(CLIENT_ID)
            .claim("nonce", NONCE)
            .expirationTime(futureDate())
            .build();
    String plainToken = new PlainJWT(claims).serialize();

    assertThatThrownBy(() -> verifier.verify(plainToken, NONCE))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getReason())
        .isEqualTo(BoomerangError.AUTH_TOKEN_INVALID.getReason());
  }

  @Test
  void rejectsAWrongIssuerToken() throws Exception {
    String token =
        signedToken(rsaKey, "https://not-the-trusted-issuer.example", CLIENT_ID, NONCE, futureDate());

    assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(BoomerangException.class);
  }

  @Test
  void rejectsAnExpiredToken() throws Exception {
    String token = signedToken(rsaKey, ISSUER, CLIENT_ID, NONCE, pastDate());

    assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(BoomerangException.class);
  }

  @Test
  void rejectsATokenSignedByAnUntrustedKey() throws Exception {
    RSAKey untrustedKey = new RSAKeyGenerator(2048).keyID("untrusted-key").generate();
    String token = signedToken(untrustedKey, ISSUER, CLIENT_ID, NONCE, futureDate());

    assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(BoomerangException.class);
  }

  @Test
  void rejectsAMismatchedNonce() throws Exception {
    String token = signedToken(rsaKey, ISSUER, CLIENT_ID, NONCE, futureDate());

    assertThatThrownBy(() -> verifier.verify(token, "a-different-nonce"))
        .isInstanceOf(BoomerangException.class);
  }

  @Test
  void requiresIssuerToBeConfigured() {
    lenient()
        .when(settingsService.getSettingConfig("auth", "oidc.issuer"))
        .thenReturn(configOf(""));

    assertThatThrownBy(() -> verifier.verify("anything", NONCE))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getReason())
        .isEqualTo(BoomerangError.AUTH_NOT_CONFIGURED.getReason());
  }

  private static SettingConfig configOf(String value) {
    SettingConfig config = new SettingConfig();
    config.setValue(value);
    return config;
  }

  private static String signedToken(
      RSAKey signingKey, String issuer, String audience, String nonce, Date expiry)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("user-1")
            .issuer(issuer)
            .audience(audience)
            .claim("email", "person@example.test")
            .claim("given_name", "Person")
            .claim("family_name", "Example")
            .claim("nonce", nonce)
            .issueTime(new Date())
            .expirationTime(expiry)
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private static Date futureDate() {
    return new Date(System.currentTimeMillis() + 3_600_000L);
  }

  private static Date pastDate() {
    return new Date(System.currentTimeMillis() - 3_600_000L);
  }
}
