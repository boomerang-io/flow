package io.boomerang.core.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The encryption key is required: a blank secret or salt refuses startup. */
class EncryptionConfigTest {

  @Test
  void aSecretAndSaltAreAccepted() {
    assertThatCode(() -> config("a-secret", "a-salt").requireKey()).doesNotThrowAnyException();
  }

  @Test
  void aBlankSecretRefusesStartup() {
    assertThatThrownBy(() -> config("", "a-salt").requireKey())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("flow.encrypt.secret");
  }

  @Test
  void aBlankSaltRefusesStartup() {
    assertThatThrownBy(() -> config("a-secret", " ").requireKey())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("flow.encrypt.salt");
  }

  private static EncryptionConfig config(String secret, String salt) {
    EncryptionConfig config = new EncryptionConfig();
    config.setSecretKey(secret);
    config.setSalt(salt);
    return config;
  }
}
