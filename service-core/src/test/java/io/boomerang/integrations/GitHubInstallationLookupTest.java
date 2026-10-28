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
import io.boomerang.integrations.model.GHInstallationsResponse;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The installation shown in the GitHub dialog is read through the external client, so the
 * operator's proxy, trust and timeout settings apply: the installation with a JWT the App signed,
 * then every page of its repositories with an installation token. Any failure is {@code
 * INTEGRATION_INVALID_REF}.
 */
class GitHubInstallationLookupTest {

  private static final String APP_ID = "424242";
  private static final String INSTALLATION_URL = "https://api.github.com/app/installations/{id}";
  private static final String REPOSITORIES_URL =
      "https://api.github.com/installation/repositories?per_page=100&page={page}";

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
  void theInstallationAndEveryPageOfItsRepositoriesAreReadThroughTheExternalClient() {
    when(restTemplate.exchange(
            eq(INSTALLATION_URL), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5201)))
        .thenReturn(
            ResponseEntity.ok(
                Map.of(
                    "id", 5201,
                    "app_id", 424242,
                    "events", List.of("push", "pull_request"),
                    "account",
                        Map.of(
                            "login", "acme",
                            "id", 9001,
                            "type", "Organization",
                            "html_url", "https://github.com/acme"))));
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5201)))
        .thenReturn(ResponseEntity.ok(Map.of("token", "ghs_lookup")));
    when(restTemplate.exchange(
            eq(REPOSITORIES_URL), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(1)))
        .thenReturn(ResponseEntity.ok(Map.of("total_count", 102, "repositories", repositories(0, 100))));
    when(restTemplate.exchange(
            eq(REPOSITORIES_URL), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(2)))
        .thenReturn(ResponseEntity.ok(Map.of("total_count", 102, "repositories", repositories(100, 102))));

    GHInstallationsResponse response = (GHInstallationsResponse) service.getInstallation(5201).getBody();

    assertThat(response.getInstallationId()).isEqualTo(5201);
    assertThat(response.getAppId()).isEqualTo(424242);
    assertThat(response.getOrgSlug()).isEqualTo("acme");
    assertThat(response.getOrgId()).isEqualTo(9001);
    assertThat(response.getOrgType()).isEqualTo("Organization");
    assertThat(response.getOrgUrl()).isEqualTo("https://github.com/acme");
    assertThat(response.getEvents()).containsExactly("push", "pull_request");
    assertThat(response.getRepositories()).hasSize(102).startsWith("repo-0").endsWith("repo-101");

    // The installation is read as the App; the repositories as the installation.
    ArgumentCaptor<HttpEntity<?>> installation = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(eq(INSTALLATION_URL), eq(HttpMethod.GET), installation.capture(), any(ParameterizedTypeReference.class), eq(5201));
    String jwt = installation.getValue().getHeaders().getFirst("Authorization").substring("Bearer ".length());
    assertThat(Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(jwt).getPayload().getIssuer())
        .isEqualTo(APP_ID);
    ArgumentCaptor<HttpEntity<?>> page = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(eq(REPOSITORIES_URL), eq(HttpMethod.GET), page.capture(), any(ParameterizedTypeReference.class), eq(2));
    assertThat(page.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer ghs_lookup");
  }

  @Test
  void anInstallationGitHubDoesNotKnowIsAnInvalidReference() {
    when(restTemplate.exchange(
            eq(INSTALLATION_URL), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class), eq(5202)))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> service.getInstallation(5202))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_INVALID_REF"));
  }

  private static List<Map<String, Object>> repositories(int from, int to) {
    return IntStream.range(from, to).mapToObj(i -> Map.<String, Object>of("name", "repo-" + i)).toList();
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
