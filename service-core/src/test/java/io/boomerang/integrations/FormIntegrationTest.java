package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.TokenService;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.enums.IntegrationKind;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.Integration;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import jakarta.servlet.Filter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A form integration, Google or Microsoft, is linked by the values a workspace enters: they become
 * managed workspace parameters, a secret encrypted at rest and shown as the redaction marker, edited
 * by sharing again, removed by disconnecting. A missing required field, an unknown field and values
 * for an app integration are refused, and the audit event names a changed secret, never its value.
 */
class FormIntegrationTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MARKER = DataAdapterUtil.REDACTED;
  private static final String GOOGLE_KEY =
      "{\"type\":\"service_account\",\"client_email\":\"flow@acme.iam.gserviceaccount.com\","
          + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nMIIEvQ\\n-----END PRIVATE KEY-----\\n\"}";

  @Autowired private IntegrationService integrationService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TokenService tokenService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  @BeforeEach
  void turnOnWorkspaceParameters() {
    setFeatureSetting("workspaceParameters", true);
  }

  @AfterEach
  void restore() {
    setFeatureSetting("workspaceParameters", true);
    SettingEntity audit = settingsRepository.findOneByKey("audit");
    if (audit != null) {
      settingsRepository.delete(audit);
    }
  }

  @Test
  void connectingGoogleWritesTheKeyEncryptedAndReportsItLinked() {
    String workspace = workspace("form-google-ws");

    integrationService.share("google", workspace, true, Map.of("google-service-account-key", GOOGLE_KEY));

    WorkspaceEntity stored = workspaceEntity(workspace);
    AbstractParam key = param(stored, "google-service-account-key");
    assertThat(key.isReadOnly()).isTrue();
    assertThat(key.getValue()).isEqualTo(GOOGLE_KEY);
    assertThat(rawValue(stored.getId(), "google-service-account-key"))
        .startsWith("crypt_v1")
        .doesNotContain("service_account");
    Integration google = integration(workspace, "google");
    assertThat(google.getKind()).isEqualTo(IntegrationKind.form);
    assertThat(google.getStatus()).isEqualTo(IntegrationStatus.linked);
    assertThat(google.getShared()).isTrue();
    assertThat(google.getLink()).isNull();
    assertThat(google.getFields()).singleElement().satisfies(field -> assertThat(field.getValue()).isEqualTo(MARKER));
  }

  @Test
  void connectingMicrosoftWritesEveryFieldAndAnEditKeepsTheSecret() {
    String workspace = workspace("form-microsoft-ws");
    patch(workspace, userParam("untouched", "as-is"));
    assertThat(integration(workspace, "microsoft").getStatus()).isEqualTo(IntegrationStatus.unlinked);

    integrationService.share("microsoft", workspace, true, microsoft("tenant-1", "client-1", "secret-1"));
    WorkspaceEntity stored = workspaceEntity(workspace);
    assertThat(param(stored, "microsoft-tenant-id").getValue()).isEqualTo("tenant-1");
    assertThat(param(stored, "microsoft-client-id").getValue()).isEqualTo("client-1");
    assertThat(param(stored, "microsoft-client-secret").getValue()).isEqualTo("secret-1");
    assertThat(rawValue(stored.getId(), "microsoft-client-secret")).startsWith("crypt_v1");
    assertThat(integration(workspace, "microsoft").getFields())
        .extracting(AbstractParam::getName, AbstractParam::getValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("microsoft-tenant-id", "tenant-1"),
            org.assertj.core.groups.Tuple.tuple("microsoft-client-id", "client-1"),
            org.assertj.core.groups.Tuple.tuple("microsoft-client-secret", MARKER));

    // The marker keeps the stored secret while another field changes.
    integrationService.share("microsoft", workspace, true, microsoft("tenant-2", "client-1", MARKER));
    stored = workspaceEntity(workspace);
    assertThat(param(stored, "microsoft-tenant-id").getValue()).isEqualTo("tenant-2");
    assertThat(param(stored, "microsoft-client-secret").getValue()).isEqualTo("secret-1");

    // Managed: the workspace cannot change it.
    assertThatThrownBy(() -> patch(workspace, userParam("microsoft-tenant-id", "tenant-3")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "PARAMS_MANAGED_BY_INTEGRATION");

    integrationService.share("microsoft", workspace, false, null);
    stored = workspaceEntity(workspace);
    assertThat(stored.getParameters()).extracting(AbstractParam::getName).containsExactly("untouched");
    assertThat(integration(workspace, "microsoft").getStatus()).isEqualTo(IntegrationStatus.unlinked);
  }

  @Test
  void aMissingRequiredFieldAnUnknownFieldAndValuesForAnAppAreRefused() {
    String workspace = workspace("form-refused-ws");

    assertThatThrownBy(
            () ->
                integrationService.share(
                    "microsoft",
                    workspace,
                    true,
                    Map.of("microsoft-tenant-id", "tenant-1", "microsoft-client-id", "client-1")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "INTEGRATION_FIELD_REQUIRED");
    Map<String, String> unknown = new HashMap<>(microsoft("tenant-1", "client-1", "secret-1"));
    unknown.put("microsoft-region", "au");
    assertThatThrownBy(() -> integrationService.share("microsoft", workspace, true, unknown))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "INTEGRATION_FIELD_UNKNOWN");
    assertThatThrownBy(
            () -> integrationService.share("github", workspace, true, Map.of("github-url", "https://ghe.acme")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "INTEGRATION_VALUES_NOT_ACCEPTED");
    assertThat(workspaceEntity(workspace).getParameters()).isNullOrEmpty();
  }

  @Test
  void connectingIsRefusedWithWorkspaceParametersOffOrAUserParameterOfTheName() {
    String workspace = workspace("form-blocked-ws");
    patch(workspace, userParam("google-service-account-key", "mine"));

    assertThatThrownBy(
            () -> integrationService.share("google", workspace, true, Map.of("google-service-account-key", GOOGLE_KEY)))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "INTEGRATION_PARAM_EXISTS");

    String other = workspace("form-disabled-ws");
    setFeatureSetting("workspaceParameters", false);
    assertThatThrownBy(
            () -> integrationService.share("google", other, true, Map.of("google-service-account-key", GOOGLE_KEY)))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "INTEGRATION_PARAMS_DISABLED");
    assertThat(param(workspaceEntity(workspace), "google-service-account-key").getValue()).isEqualTo("mine");
  }

  @Test
  void theAuditEventNamesTheSecretSetAndClearedOverHttp() throws Exception {
    captureAudit();
    String workspace = workspace("form-audit-ws");
    MockMvc mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    String bearer = bearer();

    share(mockMvc, bearer, workspace, true, microsoft("tenant-1", "client-1", "audit-secret-0001"));
    assertThat(detail(event(workspace, 1))).isEqualTo(Map.of("secretsChanged", List.of("microsoft-client-secret")));

    share(mockMvc, bearer, workspace, true, microsoft("tenant-2", "client-1", MARKER));
    assertThat(detail(event(workspace, 2))).as("a kept secret is not named").isNull();

    share(mockMvc, bearer, workspace, false, null);
    assertThat(detail(event(workspace, 3))).isEqualTo(Map.of("secretsCleared", List.of("microsoft-client-secret")));

    assertThat(JSON.writeValueAsString(events(workspace))).doesNotContain("audit-secret-0001");
  }

  private void share(MockMvc mockMvc, String bearer, String workspace, boolean shared, Map<String, String> values)
      throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("workspace", workspace);
    body.put("shared", shared);
    if (values != null) {
      body.put("values", values);
    }
    mockMvc
        .perform(
            post("/api/v2/integration/microsoft/share")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private static Map<String, String> microsoft(String tenant, String client, String secret) {
    return Map.of(
        "microsoft-tenant-id", tenant, "microsoft-client-id", client, "microsoft-client-secret", secret);
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    workspaceService.create(request);
    return name;
  }

  private void patch(String workspace, AbstractParam param) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setParameters(List.of(param));
    workspaceService.patch(workspace, request);
  }

  private static AbstractParam userParam(String name, String value) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("text");
    param.setValue(value);
    return param;
  }

  private Integration integration(String workspace, String type) {
    return integrationService.get(workspace).stream()
        .filter(integration -> type.equals(integration.getType()))
        .findFirst()
        .orElseThrow();
  }

  private WorkspaceEntity workspaceEntity(String name) {
    return workspaceRepository.findByNameIgnoreCase(name).orElseThrow();
  }

  private static AbstractParam param(WorkspaceEntity workspace, String name) {
    return workspace.getParameters().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
  }

  // The value as stored, before the load hook decrypts it.
  private String rawValue(String workspaceId, String name) {
    Document stored =
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(WorkspaceEntity.class))
            .find(new Document("_id", new org.bson.types.ObjectId(workspaceId)))
            .first();
    return stored.getList("parameters", Document.class).stream()
        .filter(p -> name.equals(p.getString("name")))
        .map(p -> p.getString("value"))
        .findFirst()
        .orElseThrow();
  }

  private String bearer() {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("form-integration-" + UUID.randomUUID());
    request.setPermissions(List.of("**/**"));
    return "Bearer " + tokenService.create(request).getToken();
  }

  private void captureAudit() {
    SettingEntity settings = new SettingEntity();
    settings.setKey("audit");
    settings.setName("Audit");
    settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
    settingsRepository.save(settings);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }

  private List<AuditEventEntity> events(String workspace) {
    return auditEventRepository.findAll().stream()
        .filter(e -> "integration".equals(e.getResourceType()) && "microsoft".equals(e.getResourceId()))
        .filter(e -> workspace.equals(e.getWorkspaceId()))
        .sorted(Comparator.comparing(AuditEventEntity::getTime))
        .toList();
  }

  private AuditEventEntity event(String workspace, int nth) {
    awaitEngine("integration audit event " + nth).until(() -> events(workspace).size() >= nth);
    return events(workspace).get(nth - 1);
  }

  private static Object detail(AuditEventEntity event) {
    return (event.getPayload() == null) ? null : event.getPayload().get("detail");
  }
}
