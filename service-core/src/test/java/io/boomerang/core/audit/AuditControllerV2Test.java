package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The admin audit query surface: what the endpoint answers, which filters narrow it, the default
 * window it applies, and who it refuses. Events are seeded directly so the assertions do not
 * depend on what else the shared database holds - every query is pinned to this class's own
 * resource type.
 */
class AuditControllerV2Test extends AbstractEngineIntegrationTest {

  private static final String RESOURCE_TYPE = "audit-query-fixture";
  private static final Optional<List<String>> OWN_FIXTURES =
      Optional.of(List.of(RESOURCE_TYPE));

  @Autowired private AuditControllerV2 auditController;
  @Autowired private AuditEventRepository auditEventRepository;

  @BeforeEach
  void seedTrail() {
    auditEventRepository.deleteAll(existingFixtures());
    if (settingsRepository.findOneByKey(AuditEventEmitter.SETTINGS_KEY) == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey(AuditEventEmitter.SETTINGS_KEY);
      settings.setName("Audit");
      settings.setConfig(
          List.of(
              config("enabled", "true"), config("level", "ALL"), config("retentionDays", "120")));
      settingsRepository.save(settings);
    }
    Instant now = Instant.now();
    save("newest", now.minus(1, ChronoUnit.HOURS), "bfu_jane", "Jane Doe", "ws-one",
        AuditAction.DELETE, AuditOutcome.SUCCESS, AuditLevel.DESTRUCTIVE);
    save("middle", now.minus(2, ChronoUnit.DAYS), "bfu_sam", "Sam Patel", "ws-two",
        AuditAction.UPDATE, AuditOutcome.FAILED, AuditLevel.WRITE);
    save("oldest-in-window", now.minus(10, ChronoUnit.DAYS), "bfk_robot", "Nightly Robot", "ws-one",
        AuditAction.READ, AuditOutcome.DENIED, AuditLevel.ALL);
    save("outside-window", now.minus(45, ChronoUnit.DAYS), "bfu_jane", "Jane Doe", "ws-one",
        AuditAction.CREATE, AuditOutcome.SUCCESS, AuditLevel.WRITE);
  }

  /** Leaves the shared database as the other test classes expect it: capture off, no fixtures. */
  @AfterEach
  void clearTrail() {
    auditEventRepository.deleteAll(existingFixtures());
    SettingEntity settings = settingsRepository.findOneByKey(AuditEventEmitter.SETTINGS_KEY);
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  @Test
  void bothRoutesCarryTheAdminAuthCriteria() throws NoSuchMethodException {
    for (Method method : AuditControllerV2.class.getDeclaredMethods()) {
      if (!method.getName().equals("query") && !method.getName().equals("stats")) {
        continue;
      }
      AuthCriteria criteria = method.getAnnotation(AuthCriteria.class);
      assertThat(criteria).as("@AuthCriteria on %s", method.getName()).isNotNull();
      assertThat(criteria.resource()).isEqualTo(PermissionResource.SYSTEM);
      assertThat(criteria.action()).isEqualTo(PermissionAction.READ);
      assertThat(criteria.assignableScopes())
          .containsExactlyInAnyOrder(AuthScope.session, AuthScope.user, AuthScope.global);
    }
  }

  @Test
  void anAdminGetsAPageOfEventsNewestFirst() {
    Page<AuditEvent> page = query(Optional.empty(), Optional.empty(), Optional.empty());

    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).extracting(AuditEvent::resourceId)
        .containsExactly("newest", "middle", "oldest-in-window");
    assertThat(page.getContent().get(0).payload()).containsEntry("sourceIp", "10.0.0.1");
    assertThat(page.getContent().get(0).actorName()).isEqualTo("Jane Doe");
  }

  @Test
  void theDefaultWindowIsThirtyDaysAndAnExplicitFromWidensIt() {
    assertThat(query(Optional.empty(), Optional.empty(), Optional.empty()).getContent())
        .extracting(AuditEvent::resourceId)
        .doesNotContain("outside-window");

    Page<AuditEvent> widened =
        auditController.query(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OWN_FIXTURES,
            Optional.empty(), Optional.empty(),
            Optional.of(Instant.now().minus(90, ChronoUnit.DAYS)), Optional.empty(),
            0, 25, Direction.DESC);

    assertThat(widened.getTotalElements()).isEqualTo(4);
    assertThat(widened.getContent()).extracting(AuditEvent::resourceId).endsWith("outside-window");
  }

  @Test
  void ascendingOrderIsHonoured() {
    Page<AuditEvent> page =
        auditController.query(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OWN_FIXTURES,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            0, 25, Direction.ASC);

    assertThat(page.getContent()).extracting(AuditEvent::resourceId)
        .containsExactly("oldest-in-window", "middle", "newest");
  }

  @Test
  void theActorFilterMatchesPartOfAnIdOrAName() {
    assertThat(query(Optional.of("jane"), Optional.empty(), Optional.empty()).getContent())
        .extracting(AuditEvent::resourceId)
        .containsExactly("newest");
    assertThat(query(Optional.of("patel"), Optional.empty(), Optional.empty()).getContent())
        .extracting(AuditEvent::resourceId)
        .containsExactly("middle");
    assertThat(query(Optional.of("bfk_"), Optional.empty(), Optional.empty()).getContent())
        .extracting(AuditEvent::resourceId)
        .containsExactly("oldest-in-window");
  }

  @Test
  void eachOfTheActionOutcomeAndLevelFiltersNarrowsIndependently() {
    assertThat(query(Optional.empty(), Optional.of(AuditAction.UPDATE), Optional.empty())
            .getContent())
        .extracting(AuditEvent::resourceId)
        .containsExactly("middle");
    assertThat(query(Optional.empty(), Optional.empty(), Optional.of(AuditOutcome.DENIED))
            .getContent())
        .extracting(AuditEvent::resourceId)
        .containsExactly("oldest-in-window");

    Page<AuditEvent> destructive =
        auditController.query(
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(List.of(AuditLevel.DESTRUCTIVE)), OWN_FIXTURES,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            0, 25, Direction.DESC);
    assertThat(destructive.getContent()).extracting(AuditEvent::resourceId)
        .containsExactly("newest");
  }

  @Test
  void theWorkspaceAndResourceFiltersNarrow() {
    Page<AuditEvent> workspace =
        auditController.query(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OWN_FIXTURES,
            Optional.empty(), Optional.of(List.of("ws-two")), Optional.empty(), Optional.empty(),
            0, 25, Direction.DESC);
    assertThat(workspace.getContent()).extracting(AuditEvent::resourceId)
        .containsExactly("middle");

    Page<AuditEvent> resource =
        auditController.query(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OWN_FIXTURES,
            Optional.of("newest"), Optional.empty(), Optional.empty(), Optional.empty(),
            0, 25, Direction.DESC);
    assertThat(resource.getContent()).extracting(AuditEvent::resourceId)
        .containsExactly("newest");
  }

  @Test
  void statsCountTheSameWindowByOutcomeAndReportTheCaptureConfiguration() {
    AuditStats stats =
        auditController.stats(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OWN_FIXTURES,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    assertThat(stats.total()).isEqualTo(3);
    assertThat(stats.outcomes())
        .containsEntry(AuditOutcome.SUCCESS, 1L)
        .containsEntry(AuditOutcome.FAILED, 1L)
        .containsEntry(AuditOutcome.DENIED, 1L);
    assertThat(stats.captureEnabled()).isTrue();
    assertThat(stats.level()).isEqualTo(AuditLevel.ALL);
    // The TTL is floored at 60 days, so the 120 the fixture sets is what is applied.
    assertThat(stats.retentionDays()).isEqualTo(120);
    assertThat(stats.from()).isNotNull();
    assertThat(stats.to()).isNull();
  }

  @Test
  void aWorkspaceScopedGrantIsRefusedEvenThoughItsPermissionStringMatches() {
    identityWith(PermissionScope.workspace);

    assertThatThrownBy(() -> query(Optional.empty(), Optional.empty(), Optional.empty()))
        .isInstanceOf(BoomerangException.class)
        .extracting(error -> ((BoomerangException) error).getCode())
        .isEqualTo(BoomerangError.PERMISSION_DENIED.getCode());

    assertThatThrownBy(
            () ->
                auditController.stats(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    OWN_FIXTURES, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(BoomerangException.class);
  }

  private Page<AuditEvent> query(
      Optional<String> actor, Optional<AuditAction> action, Optional<AuditOutcome> outcome) {
    return auditController.query(
        actor,
        action.map(List::of),
        outcome.map(List::of),
        Optional.empty(),
        OWN_FIXTURES,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        0,
        25,
        Direction.DESC);
  }

  private List<AuditEventEntity> existingFixtures() {
    return auditEventRepository.findAll().stream()
        .filter(event -> RESOURCE_TYPE.equals(event.getResourceType()))
        .toList();
  }

  private void save(
      String resourceId,
      Instant time,
      String actorId,
      String actorName,
      String workspaceId,
      AuditAction action,
      AuditOutcome outcome,
      AuditLevel level) {
    AuditEventEntity event = new AuditEventEntity();
    event.setTime(Date.from(time));
    event.setSubject(resourceId);
    event.setActorId(actorId);
    event.setActorName(actorName);
    event.setActorType("user");
    event.setWorkspaceId(workspaceId);
    event.setAction(action.name());
    event.setResourceType(RESOURCE_TYPE);
    event.setResourceId(resourceId);
    event.setResourceName(resourceId + " fixture");
    event.setOutcome(outcome.name());
    event.setLevel(level.name());
    event.getPayload().put("sourceIp", "10.0.0.1");
    event.getPayload().put("requestPath", "/api/v2/workspace/" + workspaceId);
    auditEventRepository.save(event);
  }

  private static void identityWith(PermissionScope scope) {
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal("workspace-owner");
    principal.setPermissions(
        List.of(new ResolvedPermissions(scope, "ws-one", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
