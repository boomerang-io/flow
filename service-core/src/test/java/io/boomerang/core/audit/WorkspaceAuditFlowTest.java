package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceControllerV2;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One annotated controller flow end to end: the {@code @Audited} annotation on {@code
 * WorkspaceControllerV2.createWorkspace} drives the aspect, the capture gate reads the seeded
 * {@code audit} settings document, and the async writer lands a flat event in the {@code audit}
 * collection. The base class establishes the standard global test identity.
 */
class WorkspaceAuditFlowTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceControllerV2 workspaceController;
  @Autowired private AuditEventRepository auditEventRepository;

  @BeforeEach
  void seedAuditSettings() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
  }

  /** Leaves the shared database as the other test classes expect it: capture off. */
  @AfterEach
  void removeAuditSettings() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }

  @Test
  void anAuditedControllerCallLandsOneFlatEventInTheAuditCollection() {
    String name = "audit-flow-ws";
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName("Audit Flow WS");

    workspaceController.createWorkspace(request);

    awaitEngine("audit event written")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events =
                  auditEventRepository.findAll().stream()
                      .filter(event -> name.equals(event.getResourceId()))
                      .toList();
              assertThat(events).hasSize(1);
              AuditEventEntity event = events.get(0);
              assertThat(event.getAction()).isEqualTo("CREATE");
              assertThat(event.getResourceType()).isEqualTo("workspace");
              assertThat(event.getResourceName()).isEqualTo("Audit Flow WS");
              assertThat(event.getWorkspaceId()).isEqualTo(name);
              assertThat(event.getOutcome()).isEqualTo("SUCCESS");
              assertThat(event.getLevel()).isEqualTo("WRITE");
              assertThat(event.getActorId()).isEqualTo("integration-test-principal");
              assertThat(event.getActorType()).isEqualTo("global");
              assertThat(event.getTime()).isNotNull();
              assertThat(event.getCreatedAt()).isNotNull();
            });
  }

  @Test
  void aRefusedControllerCallStillRecordsTheAttemptAndRethrows() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("");
    request.setDisplayName("Blank Name WS");

    assertThatThrownBy(() -> workspaceController.createWorkspace(request))
        .isInstanceOf(BoomerangException.class);

    awaitEngine("failed audit event written")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events =
                  auditEventRepository.findAll().stream()
                      .filter(
                          event ->
                              "FAILED".equals(event.getOutcome())
                                  && "workspace".equals(event.getResourceType()))
                      .toList();
              assertThat(events).isNotEmpty();
              assertThat(events.get(0).getPayload()).containsKey("errorSummary");
            });
  }
}
