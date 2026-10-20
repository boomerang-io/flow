package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.core.SettingsService;
import io.boomerang.core.model.SettingConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Slack signs each request with v0= + HMAC-SHA256 of v0:{timestamp}:{body}. The expected signature
 * below was computed outside Java: printf 'v0:1700000000:<body>' | openssl dgst -sha256 -hmac
 * test-signing-secret.
 */
class SlackSignatureTest {

  private static final String SECRET = "test-signing-secret";
  private static final String TIMESTAMP = "1700000000";
  private static final String BODY = "payload=%7B%22type%22%3A%22view_submission%22%7D";
  private static final String SIGNATURE =
      "v0=ee22ab3b916fddf08cc7d0d33fdca38db679df751e02bb5613f005bd65ae007a";
  private static final Instant SENT = Instant.ofEpochSecond(1700000000L);

  @Test
  void aRequestSlackSignedIsAccepted() {
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, TIMESTAMP, BODY, SENT.plusSeconds(60)))
        .isTrue();
  }

  @Test
  void anotherSecretOrAnAlteredBodyIsRefused() {
    assertThat(SlackService.isSignedBy("other-secret", SIGNATURE, TIMESTAMP, BODY, SENT)).isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, TIMESTAMP, BODY + "&x=1", SENT)).isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, "1700000001", BODY, SENT)).isFalse();
  }

  @Test
  void aTimestampMoreThanFiveMinutesAwayIsRefused() {
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, TIMESTAMP, BODY, SENT.plusSeconds(301)))
        .isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, TIMESTAMP, BODY, SENT.minusSeconds(301)))
        .isFalse();
  }

  @Test
  void aMissingOrMalformedPartIsRefused() {
    assertThat(SlackService.isSignedBy(SECRET, null, TIMESTAMP, BODY, SENT)).isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, null, BODY, SENT)).isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, TIMESTAMP, null, SENT)).isFalse();
    assertThat(SlackService.isSignedBy(SECRET, SIGNATURE, "not-a-time", BODY, SENT)).isFalse();
  }

  @Test
  void withNoSigningSecretConfiguredNothingIsAccepted() {
    SettingsService settings = mock(SettingsService.class);
    SettingConfig blank = new SettingConfig();
    blank.setValue("");
    when(settings.getSettingConfig("integration", "slack.signingSecret")).thenReturn(blank);
    SlackService slack = new SlackService(settings, null, null, null);

    assertThat(slack.verifySignature(SIGNATURE, TIMESTAMP, BODY)).isFalse();

    when(settings.getSettingConfig("integration", "slack.signingSecret"))
        .thenThrow(new IllegalArgumentException("Unable to find configuration object"));
    assertThat(slack.verifySignature(SIGNATURE, TIMESTAMP, BODY)).isFalse();
  }
}
