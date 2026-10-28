package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * An installation token is minted through the external client, so the operator's proxy, trust and
 * timeout settings apply: a POST to GitHub's access-token route for the installation, carrying a
 * JWT the App's private key signs with the App id as issuer. A refusal or a reply with no token is
 * {@code INTEGRATION_TOKEN_FAILED}.
 */
class GitHubInstallationTokenTest {

  private static final String APP_ID = "424242";

  private final SettingsService settings = mock(SettingsService.class);
  private final RestTemplate restTemplate = mock(RestTemplate.class);
  private final GitHubService service =
      new GitHubService(
          settings,
          mock(RelationshipService.class),
          mock(IntegrationsRepository.class),
          mock(IntegrationParameterService.class),
          restTemplate);

  private KeyPair keys;

  @BeforeEach
  void configureTheApp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    when(settings.getSettingConfig("integration", "github.appId")).thenReturn(setting(APP_ID));
    when(settings.getSettingConfig("integration", "github.jwt")).thenReturn(setting(pkcs1Pem(keys)));
  }

  @Test
  void theTokenIsAskedForThroughTheExternalClientWithAJwtTheAppSigned() {
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5101)))
        .thenReturn(ResponseEntity.ok(Map.of("token", "ghs_minted", "expires_at", "2026-10-28T12:00:00Z")));

    assertThat(service.installationToken("5101")).isEqualTo("ghs_minted");

    ArgumentCaptor<HttpEntity<?>> request = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            eq("https://api.github.com/app/installations/{id}/access_tokens"),
            eq(HttpMethod.POST),
            request.capture(),
            any(ParameterizedTypeReference.class),
            eq(5101));
    assertThat(request.getValue().getHeaders().getAccept())
        .containsExactly(MediaType.parseMediaType("application/vnd.github+json"));
    String jwt = request.getValue().getHeaders().getFirst("Authorization").substring("Bearer ".length());
    Claims claims = Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(jwt).getPayload();
    assertThat(claims.getIssuer()).isEqualTo(APP_ID);
  }

  @Test
  void aRefusalOrAReplyWithoutATokenIsTokenFailed() {
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5102)))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5103)))
        .thenReturn(ResponseEntity.ok(Map.of("message", "no token here")));

    for (String installation : List.of("5102", "5103")) {
      assertThatThrownBy(() -> service.installationToken(installation))
          .isInstanceOfSatisfying(
              BoomerangException.class,
              e -> {
                assertThat(e.getReason()).isEqualTo("INTEGRATION_TOKEN_FAILED");
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
              });
    }
  }

  private static SettingConfig setting(String value) {
    SettingConfig config = new SettingConfig();
    config.setValue(value);
    return config;
  }

  // The App key as GitHub issues it: PKCS#1. Java encodes PKCS#8, whose 26-byte header for a
  // 2048-bit RSA key wraps the PKCS#1 key.
  private static String pkcs1Pem(KeyPair keys) {
    byte[] pkcs8 = keys.getPrivate().getEncoded();
    byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
    return "-----BEGIN RSA PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs1)
        + "\n-----END RSA PRIVATE KEY-----";
  }
}
