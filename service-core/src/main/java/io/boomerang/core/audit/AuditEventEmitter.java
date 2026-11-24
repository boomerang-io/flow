package io.boomerang.core.audit;

import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.SettingsRepository;
import io.boomerang.core.security.IdentityService;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Explicit audit emission API for sites {@link AuditAspect} cannot reach — branch-based outcomes,
 * flows where the SecurityContext is not populated, and the engine's run transition listener.
 * Annotated controller methods should use {@link Audited} instead.
 *
 * <p>Capture gates on the {@code audit} settings document ({@code enabled}, {@code level}) that
 * {@code io.boomerang.migration._0016__BuildSettingsFromSeed} builds — read per emission, like the
 * other feature settings. A missing document disables capture (only an unmigrated database lacks
 * it).
 *
 * <p>Actor rule: a tokenless HTTP caller records as {@code anonymous}, a tokenless non-HTTP call
 * site as {@code system} — an unauthenticated request is by definition not the system.
 */
@Service
public class AuditEventEmitter {

  static final String SETTINGS_KEY = "audit";

  private final AuditEventWriter writer;
  private final IdentityService identityService;
  private final SettingsRepository settingsRepository;
  private final RelationshipService relationshipService;

  public AuditEventEmitter(
      AuditEventWriter writer,
      IdentityService identityService,
      SettingsRepository settingsRepository,
      RelationshipService relationshipService) {
    this.writer = writer;
    this.identityService = identityService;
    this.settingsRepository = settingsRepository;
    this.relationshipService = relationshipService;
  }

  /**
   * Return the workspace a site names, by id or name, as the trail records it: its id and its name
   * now. One graph node read, none when the request has already resolved it. A reference no
   * workspace answers to is kept as both, so the event still says what it was given.
   */
  public AuditWorkspace workspace(String idOrName) {
    if (idOrName == null || idOrName.isBlank()) {
      return AuditWorkspace.NONE;
    }
    return relationshipService
        .findNode(RelationshipType.WORKSPACE, idOrName)
        .map(node -> new AuditWorkspace(node.getRef(), node.getSlug()))
        .orElse(new AuditWorkspace(idOrName, idOrName));
  }

  /** True when a site declared at {@code siteLevel} fires under the configured settings. */
  public boolean captureEnabled(AuditLevel siteLevel) {
    SettingEntity settings = settingsRepository.findOneByKey(SETTINGS_KEY);
    if (settings == null || settings.getConfig() == null) {
      return false;
    }
    boolean enabled = !"false".equalsIgnoreCase(configValue(settings, "enabled", "true"));
    AuditLevel configured = AuditLevel.fromString(configValue(settings, "level", null));
    return enabled && siteLevel.enabledAt(configured);
  }

  /** True when capture is on at all, whatever the level - what a reader of the trail asks. */
  public boolean captureEnabled() {
    SettingEntity settings = settingsRepository.findOneByKey(SETTINGS_KEY);
    return settings != null
        && settings.getConfig() != null
        && !"false".equalsIgnoreCase(configValue(settings, "enabled", "true"));
  }

  /** The configured capture verbosity; WRITE on an unseeded or unparseable setting. */
  public AuditLevel configuredLevel() {
    SettingEntity settings = settingsRepository.findOneByKey(SETTINGS_KEY);
    return (settings == null || settings.getConfig() == null)
        ? AuditLevel.WRITE
        : AuditLevel.fromString(configValue(settings, "level", null));
  }

  /** Emit with the actor resolved from the SecurityContext. Gate-checked. */
  public void emit(
      AuditAction action,
      AuditLevel level,
      String resourceType,
      String resourceId,
      String resourceName,
      String workspace,
      AuditOutcome outcome,
      String errorSummary,
      Map<String, Object> detail) {
    if (!captureEnabled(level)) {
      return;
    }
    AuditRequestContext http = AuditRequestContext.capture();
    AuditWorkspace owner = workspace(workspace);
    writer.persist(
        new AuditRecord(
            currentActor(http),
            owner.id(),
            owner.name(),
            action,
            level,
            resourceType,
            resourceId,
            resourceName,
            outcome,
            http.sourceIp(),
            http.userAgent(),
            http.method(),
            http.path(),
            null,
            errorSummary,
            detail,
            null));
  }

  /**
   * Emit as an explicitly resolved actor with a caller-supplied payload — for sites without a
   * request to attribute (the engine's run transition listener). The attempt already happened,
   * so the outcome is always SUCCESS. Gate-checked.
   */
  public void emitAs(
      AuditActor actor,
      AuditAction action,
      AuditLevel level,
      String resourceType,
      String resourceId,
      String resourceName,
      AuditWorkspace workspace,
      Map<String, Object> payload) {
    if (!captureEnabled(level)) {
      return;
    }
    writer.persist(
        new AuditRecord(
            actor,
            workspace.id(),
            workspace.name(),
            action,
            level,
            resourceType,
            resourceId,
            resourceName,
            AuditOutcome.SUCCESS,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            payload));
  }

  /** The current actor: the token's identity, else anonymous over HTTP, else system. */
  AuditActor currentActor(AuditRequestContext http) {
    Token token = identityService.getCurrentIdentity();
    AuditActor actor = AuditActor.from(token);
    if (actor != null) {
      return actor;
    }
    return (http.method() != null) ? AuditActor.anonymous() : AuditActor.system();
  }

  private static String configValue(SettingEntity settings, String key, String fallback) {
    return settings.getConfig().stream()
        .filter(config -> key.equals(config.getKey()))
        .findFirst()
        .map(SettingConfig::getValue)
        .orElse(fallback);
  }
}
