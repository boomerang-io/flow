package io.boomerang.integrations;

import com.spotify.github.v3.clients.JwtTokenIssuer;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.GHInstallationsResponse;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class GitHubService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String OAUTH_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
  private static final String USER_INSTALLATIONS_URL = "https://api.github.com/user/installations";
  private static final String INSTALLATION_URL = "https://api.github.com/app/installations/{id}";
  private static final String INSTALLATION_TOKEN_URL =
      "https://api.github.com/app/installations/{id}/access_tokens";
  private static final String INSTALLATION_REPOSITORIES_URL =
      "https://api.github.com/installation/repositories?per_page=100&page={page}";
  private static final MediaType GITHUB_JSON = MediaType.parseMediaType("application/vnd.github+json");
  private static final String GITHUB_APP_TYPE = "github_app";
  private static final Duration STATE_TTL = Duration.ofMinutes(10);

  @Value("${flow.apps.flow.url}")
  private String flowAppsUrl;

  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final IntegrationsRepository integrationsRepository;
  private final IntegrationParameterService integrationParameterService;
  private final RestTemplate restTemplate;

  public GitHubService(
      SettingsService settingsService,
      RelationshipService relationshipService,
      IntegrationsRepository integrationsRepository,
      IntegrationParameterService integrationParameterService,
      @Qualifier("externalRestTemplate") RestTemplate restTemplate) {
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.integrationsRepository = integrationsRepository;
    this.integrationParameterService = integrationParameterService;
    this.restTemplate = restTemplate;
  }

  /*
   * The claims recovered from a verified state - who asked for the installation, and for which
   * workspace.
   */
  private record InstallState(String workspace, String userId) {}

  /*
   * The installation, read with the App's JWT, and every repository it can reach, read with an
   * installation token. Both go through the external client, so the operator's proxy, trust and
   * timeout settings apply.
   */
  public ResponseEntity<?> getInstallation(Integer id) {
    try {
      Map<String, Object> installation = getJson(INSTALLATION_URL, appJwt(), id);
      Map<?, ?> account = (Map<?, ?>) installation.get("account");

      GHInstallationsResponse response = new GHInstallationsResponse();
      response.setAppId(((Number) installation.get("app_id")).intValue());
      response.setInstallationId(((Number) installation.get("id")).intValue());
      response.setOrgSlug((String) account.get("login"));
      response.setOrgUrl((String) account.get("html_url"));
      response.setOrgId(((Number) account.get("id")).intValue());
      response.setOrgType((String) account.get("type"));
      response.setEvents(
          ((List<?>) installation.get("events")).stream().map(String::valueOf).toList());
      response.setRepositories(repositories(installationToken(String.valueOf(id))));
      return ResponseEntity.ok(response);
    } catch (GeneralSecurityException | RuntimeException ex) {
      throw new BoomerangException(ex, BoomerangError.INTEGRATION_INVALID_REF);
    }
  }

  // Every repository the installation can reach, a page of 100 at a time until all are read.
  private List<String> repositories(String installationToken) {
    List<String> names = new LinkedList<>();
    for (int page = 1; ; page++) {
      Map<String, Object> body = getJson(INSTALLATION_REPOSITORIES_URL, installationToken, page);
      List<?> repositories = (List<?>) body.get("repositories");
      repositories.forEach(repository -> names.add((String) ((Map<?, ?>) repository).get("name")));
      if (repositories.isEmpty() || names.size() >= ((Number) body.get("total_count")).intValue()) {
        return names;
      }
    }
  }

  private Map<String, Object> getJson(String url, String bearer, Object... uriVariables) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearer);
    headers.setAccept(List.of(GITHUB_JSON));
    return restTemplate
        .exchange(
            url,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<Map<String, Object>>() {},
            uriVariables)
        .getBody();
  }

  // A short-lived JWT the App's private key signs, with the App id as issuer: how an App calls GitHub.
  private String appJwt() throws GeneralSecurityException {
    Integer appId =
        Integer.valueOf(settingsService.getSettingConfig("integration", "github.appId").getValue());
    return JwtTokenIssuer.fromPrivateKey(getPEMBytes()).getToken(appId);
  }

  public ResponseEntity<?> getInstallationForWorkspace(String workspace) {
    List<String> refs =
        relationshipService.findNodeRefs(RelationshipType.WORKSPACE, workspace, RelationshipType.INTEGRATION);
    if (!refs.isEmpty()) {
      Optional<IntegrationsEntity> optEntity = integrationsRepository.findById(refs.get(0));
      if (optEntity.isPresent()) {
        return this.getInstallation(Integer.valueOf(optEntity.get().getRef()));
      }
    }
    throw new BoomerangException(BoomerangError.INTEGRATION_INVALID_REF);
  }

  /*
   * A fresh installation access token, valid for an hour, carrying the App's permissions on that
   * installation. It is asked for with a JWT signed by the App's private key, through the external
   * client so the operator's proxy, trust and timeout settings apply. Neither is ever logged.
   */
  public String installationToken(String installationRef) {
    Map<String, Object> body;
    try {
      Integer installationId = Integer.valueOf(installationRef);
      HttpHeaders headers = new HttpHeaders();
      headers.setBearerAuth(appJwt());
      headers.setAccept(List.of(GITHUB_JSON));
      body =
          restTemplate
              .exchange(
                  INSTALLATION_TOKEN_URL,
                  HttpMethod.POST,
                  new HttpEntity<>(headers),
                  new ParameterizedTypeReference<Map<String, Object>>() {},
                  installationId)
              .getBody();
    } catch (GeneralSecurityException | RuntimeException ex) {
      throw new BoomerangException(ex, BoomerangError.INTEGRATION_TOKEN_FAILED, "GitHub");
    }
    if (body != null && body.get("token") instanceof String token && !token.isBlank()) {
      return token;
    }
    throw new BoomerangException(BoomerangError.INTEGRATION_TOKEN_FAILED, "GitHub");
  }

  /*
   * An installation token lives an hour, so every workspace sharing one gets a fresh token every
   * 45 minutes, and once shortly after this instance starts. Every instance runs this; each mint is
   * independent and any of them is valid, so a duplicate costs one GitHub call and one write.
   */
  @Scheduled(
      initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(60000)}",
      fixedDelay = 45,
      timeUnit = TimeUnit.MINUTES)
  public void refreshSharedTokens() {
    IntegrationTemplateEntity template = integrationParameterService.template(GITHUB_APP_TYPE);
    List<AbstractParam> credentials =
        IntegrationParameterService.fields(template).stream()
            .filter(field -> "password".equals(field.getType()))
            .toList();
    if (credentials.isEmpty()) {
      return;
    }
    for (IntegrationsEntity installation : integrationsRepository.findByType(GITHUB_APP_TYPE)) {
      WorkspaceEntity workspace =
          integrationParameterService.workspace(
              relationshipService.getParentByLabel(
                  RelationshipLabel.HAS_INTEGRATION, RelationshipType.INTEGRATION, installation.getId()));
      if (workspace == null || !integrationParameterService.isShared(workspace, template)) {
        continue;
      }
      try {
        String token = installationToken(installation.getRef());
        credentials.forEach(field -> integrationParameterService.refresh(workspace, field, token));
      } catch (RuntimeException ex) {
        LOGGER.warn(
            "GitHub installation {} - the token shared with workspace {} could not be refreshed: {}",
            installation.getRef(),
            workspace.getName(),
            ex.getMessage());
      }
    }
  }

  private byte[] getPEMBytes() {
    final String pem = settingsService.getSettingConfig("integration", "github.jwt").getValue();
    final String RSA_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    final String RSA_END = "-----END RSA PRIVATE KEY-----";

    String middle = pem.replace(RSA_BEGIN, "").replace(RSA_END, "").trim();
    String[] split = middle.split(" ");

    StringBuilder builder = new StringBuilder();
    builder.append(RSA_BEGIN).append("\n");
    for (String s : split) {
      builder.append(s).append("\n");
    }
    builder.append(RSA_END);

    return builder.toString().getBytes();
  }

  /*
   * Issues a signed, short-lived state carrying the workspace and the requesting user - appended
   * to the install link so the callback never has to trust anything the browser sends back
   * unverified.
   */
  public String createSignedState(String workspace, String userId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .claim("workspace", workspace)
        .claim("userId", userId)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(STATE_TTL)))
        .signWith(stateSigningKey(), Jwts.SIG.HS256)
        .compact();
  }

  private InstallState verifySignedState(String state) {
    try {
      Claims claims = Jwts.parser().verifyWith(stateSigningKey()).build().parseSignedClaims(state).getPayload();
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

  /*
   * Derives the HMAC signing key from the GitHub App private key already held in settings, rather
   * than introducing a dedicated secret - it is server-held, shared across instances, and never
   * exposed to a client. SHA-256 normalises it to a fixed-length key rather than using the PEM
   * bytes directly.
   */
  private SecretKey stateSigningKey() {
    final String pem = settingsService.getSettingConfig("integration", "github.jwt").getValue();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Keys.hmacShaKeyFor(digest.digest(pem.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /*
   * Handles the GitHub App setup callback: verifies the signed state, confirms the initiating
   * user belongs to the claimed workspace, then confirms the installer's own GitHub account can
   * see the installation before linking it - GitHub's docs warn the installation_id query
   * parameter can be spoofed, so it is never trusted on its own.
   */
  public ResponseEntity<?> handleInstallCallback(Integer installationId, String code, String state) {
    InstallState installState = verifySignedState(state);

    if (!relationshipService.hasNodes(
        RelationshipType.USER,
        installState.userId(),
        RelationshipType.WORKSPACE,
        Optional.of(List.of(installState.workspace())),
        Optional.empty(),
        Optional.empty())) {
      LOGGER.warn(
          "GitHub callback - user {} is not a member of workspace {}",
          installState.userId(),
          installState.workspace());
      throw new BoomerangException(BoomerangError.INTEGRATION_UNAUTHORIZED);
    }

    if (!installerOwnsInstallation(code, installationId)) {
      throw new BoomerangException(BoomerangError.INTEGRATION_INSTALL_MISMATCH);
    }

    IntegrationsEntity entity = persistInstallation(installationId);
    linkToWorkspace(entity, installState.workspace());

    try {
      return ResponseEntity.status(HttpStatus.FOUND)
          .location(new URI(flowAppsUrl + "/" + installState.workspace() + "/integrations"))
          .build();
    } catch (URISyntaxException ex) {
      throw new BoomerangException(ex, BoomerangError.INTEGRATION_INVALID_STATE);
    }
  }

  /*
   * Exchanges the OAuth code for a user access token and confirms the installation is visible to
   * that user - the step that stops someone linking an installation they do not own.
   */
  private boolean installerOwnsInstallation(String code, Integer installationId) {
    final String clientId = settingsService.getSettingConfig("integration", "github.clientId").getValue();
    final String clientSecret =
        settingsService.getSettingConfig("integration", "github.clientSecret").getValue();

    try {
      HttpHeaders tokenHeaders = new HttpHeaders();
      tokenHeaders.setContentType(MediaType.APPLICATION_JSON);
      tokenHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
      Map<String, String> tokenRequest =
          Map.of("client_id", clientId, "client_secret", clientSecret, "code", code);
      ResponseEntity<Map<String, Object>> tokenResponse =
          restTemplate.exchange(
              OAUTH_ACCESS_TOKEN_URL,
              HttpMethod.POST,
              new HttpEntity<>(tokenRequest, tokenHeaders),
              new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<String, Object> tokenBody = tokenResponse.getBody();
      if (tokenBody == null || !(tokenBody.get("access_token") instanceof String userAccessToken)) {
        LOGGER.warn("GitHub OAuth code exchange did not return an access_token");
        return false;
      }

      HttpHeaders installHeaders = new HttpHeaders();
      installHeaders.setBearerAuth(userAccessToken);
      installHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
      ResponseEntity<Map<String, Object>> installationsResponse =
          restTemplate.exchange(
              USER_INSTALLATIONS_URL,
              HttpMethod.GET,
              new HttpEntity<>(installHeaders),
              new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<String, Object> installationsBody = installationsResponse.getBody();
      if (installationsBody == null
          || !(installationsBody.get("installations") instanceof List<?> installations)) {
        return false;
      }
      return installations.stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .anyMatch(i -> installationId.equals(Integer.valueOf(String.valueOf(i.get("id")))));
    } catch (RestClientException ex) {
      LOGGER.error("GitHub OAuth verification failed", ex);
      return false;
    }
  }

  private IntegrationsEntity persistInstallation(Integer installationId) {
    String ref = String.valueOf(installationId);
    Optional<IntegrationsEntity> existing = integrationsRepository.findByRef(ref);
    if (existing.isPresent()) {
      return existing.get();
    }
    IntegrationsEntity entity = new IntegrationsEntity();
    entity.setType(GITHUB_APP_TYPE);
    entity.setRef(ref);
    entity.setStatus(IntegrationStatus.linked);
    entity = integrationsRepository.save(entity);
    relationshipService.createNode(RelationshipType.INTEGRATION, entity.getId(), "", Optional.empty());
    return entity;
  }

  private void linkToWorkspace(IntegrationsEntity entity, String workspace) {
    if (relationshipService
        .filter(
            RelationshipType.INTEGRATION,
            Optional.of(List.of(entity.getId())),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)))
        .isEmpty()) {
      relationshipService.createEdge(
          RelationshipType.WORKSPACE,
          workspace,
          RelationshipLabel.HAS_INTEGRATION,
          RelationshipType.INTEGRATION,
          entity.getId(),
          Optional.empty());
    }
  }

  /*
   * Self-service unlink - keyed on the installation ref (a GitHub installation id is not a Mongo
   * _id) and guarded against unlinking an installation that does not belong to the requesting
   * workspace. The parameters it managed in the workspace go with it.
   */
  public void unlinkAppInstallation(GHLinkRequest request) {
    LOGGER.debug("unlinkAppInstallation() - " + request.toString());
    Optional<IntegrationsEntity> optEntity = integrationsRepository.findByRef(request.getRef());
    if (optEntity.isPresent()) {
      IntegrationsEntity entity = optEntity.get();
      if (!relationshipService.hasNodes(
          RelationshipType.WORKSPACE,
          request.getWorkspace(),
          RelationshipType.INTEGRATION,
          Optional.of(List.of(entity.getId())),
          Optional.empty(),
          Optional.empty())) {
        throw new BoomerangException(BoomerangError.INTEGRATION_UNAUTHORIZED);
      }
      WorkspaceEntity workspace = integrationParameterService.workspace(request.getWorkspace());
      if (workspace != null) {
        integrationParameterService.unshare(
            workspace, integrationParameterService.template(GITHUB_APP_TYPE));
      }
      integrationsRepository.delete(entity);
      relationshipService.removeNodeAndEdgeByRefOrSlug(RelationshipType.INTEGRATION, entity.getId());
    }
  }

  /*
   * Verifies GitHub's installation webhook signature (X-Hub-Signature-256, HMAC-SHA256 over the
   * raw body) so an unsigned or forged payload can never trigger an unlink.
   */
  public boolean verifyWebhookSignature(String signatureHeader, String rawBody) {
    if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
      return false;
    }
    final String secret = settingsService.getSettingConfig("integration", "github.webhookSecret").getValue();
    if (secret == null || secret.isBlank()) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      String computedHex = HexFormat.of().formatHex(computed);
      String providedHex = signatureHeader.substring("sha256=".length());
      return MessageDigest.isEqual(
          computedHex.getBytes(StandardCharsets.UTF_8), providedHex.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      LOGGER.error("Unable to verify GitHub webhook signature", ex);
      return false;
    }
  }
}
