package io.boomerang.core.audit;

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
 * <p>Capture gates on the {@code audit} settings document ({@code enabled}, {@code level}) the
 * loader seeds — read per emission, like the other feature settings. A missing document disables
 * capture (only an unseeded database lacks it).
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

  public AuditEventEmitter(
      AuditEventWriter writer,
      IdentityService identityService,
      SettingsRepository settingsRepository) {
    this.writer = writer;
    this.identityService = identityService;
    this.settingsRepository = settingsRepository;
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

  /** Emit with the actor resolved from the SecurityContext. Gate-checked. */
  public void emit(
      AuditAction action,
      AuditLevel level,
      String resourceType,
      String resourceId,
      String resourceName,
      String workspaceId,
      AuditOutcome outcome,
      String errorSummary,
      String detail) {
    if (!captureEnabled(level)) {
      return;
    }
    AuditRequestContext http = AuditRequestContext.capture();
    writer.persist(
        new AuditRecord(
            currentActor(http),
            workspaceId,
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
      String workspaceId,
      Map<String, Object> payload) {
    if (!captureEnabled(level)) {
      return;
    }
    writer.persist(
        new AuditRecord(
            actor,
            workspaceId,
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
