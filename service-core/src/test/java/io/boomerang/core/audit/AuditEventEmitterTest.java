package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.SettingsRepository;
import io.boomerang.core.security.IdentityService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The capture gate reads the {@code audit} settings document: {@code enabled} switches capture,
 * {@code level} filters sites at capture time, and a missing document means no capture (only an
 * unseeded database lacks it).
 */
class AuditEventEmitterTest {

  private SettingsRepository settingsRepository;
  private AuditEventEmitter emitter;

  @BeforeEach
  void setUp() {
    settingsRepository = mock(SettingsRepository.class);
    emitter =
        new AuditEventEmitter(
            mock(AuditEventWriter.class), mock(IdentityService.class), settingsRepository);
  }

  private void auditSettings(String enabled, String level) {
    SettingEntity settings = new SettingEntity();
    settings.setKey("audit");
    settings.setConfig(List.of(config("enabled", enabled), config("level", level)));
    when(settingsRepository.findOneByKey("audit")).thenReturn(settings);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }

  @Test
  void missingSettingsDocumentDisablesCapture() {
    when(settingsRepository.findOneByKey("audit")).thenReturn(null);

    assertThat(emitter.captureEnabled(AuditLevel.WRITE)).isFalse();
  }

  @Test
  void enabledAtWriteCapturesWriteAndDestructiveButNotAll() {
    auditSettings("true", "WRITE");

    assertThat(emitter.captureEnabled(AuditLevel.DESTRUCTIVE)).isTrue();
    assertThat(emitter.captureEnabled(AuditLevel.WRITE)).isTrue();
    assertThat(emitter.captureEnabled(AuditLevel.ALL)).isFalse();
  }

  @Test
  void destructiveOnlyConfigurationSkipsWriteSites() {
    auditSettings("true", "DESTRUCTIVE");

    assertThat(emitter.captureEnabled(AuditLevel.DESTRUCTIVE)).isTrue();
    assertThat(emitter.captureEnabled(AuditLevel.WRITE)).isFalse();
  }

  @Test
  void disabledSwitchesEverythingOff() {
    auditSettings("false", "ALL");

    assertThat(emitter.captureEnabled(AuditLevel.DESTRUCTIVE)).isFalse();
  }

  @Test
  void unrecognisedLevelFallsBackToWrite() {
    auditSettings("true", "everything");

    assertThat(emitter.captureEnabled(AuditLevel.WRITE)).isTrue();
    assertThat(emitter.captureEnabled(AuditLevel.ALL)).isFalse();
  }
}
