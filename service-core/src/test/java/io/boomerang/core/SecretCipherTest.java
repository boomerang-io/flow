package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.core.model.EncryptionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Only the whole label counts as encrypted, a labelled value is never encrypted twice, and an
 * encryption failure throws instead of answering nothing.
 */
class SecretCipherTest {

  private static final String PREFIX = "crypt_v1{AESGCM|";

  private SecretCipher cipher;

  @BeforeEach
  void setUp() {
    EncryptionConfig encryptionConfig = new EncryptionConfig();
    encryptionConfig.setSecretKey("test-secret");
    encryptionConfig.setSalt("test-salt");
    cipher = new SecretCipher(encryptionConfig);
  }

  @Test
  void onlyTheWholeLabelCountsAsEncrypted() {
    assertThat(SecretCipher.isEncrypted(PREFIX + "abc}")).isTrue();
    assertThat(SecretCipher.isEncrypted("crypt_v1")).isFalse();
    assertThat(SecretCipher.isEncrypted("crypt_v1-my-password")).isFalse();
    assertThat(SecretCipher.isEncrypted(PREFIX)).isFalse();
    assertThat(SecretCipher.isEncrypted(PREFIX + "}")).isFalse();
    assertThat(SecretCipher.isEncrypted(null)).isFalse();
  }

  @Test
  void aValueThatOnlyStartsWithTheLabelIsEncryptedAndALabelledOneIsNotWrapped() {
    String encrypted = cipher.encrypt("crypt_v1-my-password");
    assertThat(encrypted).startsWith(PREFIX).endsWith("}").doesNotContain("my-password");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("crypt_v1-my-password");

    assertThat(cipher.encrypt(encrypted)).isEqualTo(encrypted);
    String undecryptable = PREFIX + "bm90LWEtY2lwaGVydGV4dA}";
    assertThat(cipher.encrypt(undecryptable)).isEqualTo(undecryptable);
    assertThat(cipher.decrypt(undecryptable)).isNull();
  }

  @Test
  void anEncryptionFailureThrowsAndNamesNoValue() {
    TextEncryptor failing =
        new TextEncryptor() {
          @Override
          public String encrypt(String text) {
            throw new IllegalArgumentException("boom");
          }

          @Override
          public String decrypt(String encryptedText) {
            throw new IllegalArgumentException("boom");
          }
        };
    ReflectionTestUtils.setField(cipher, "encryptor", failing);

    assertThatThrownBy(() -> cipher.encrypt("s3cret-value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("s3cret-value");
  }
}
