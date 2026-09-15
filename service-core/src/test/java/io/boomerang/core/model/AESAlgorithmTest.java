package io.boomerang.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The AES-256-GCM scheme that replaced a hand-rolled AES/CBC/PKCS5Padding cipher with a single
 * hardcoded IV shared by every value it ever produced. A round trip must still work with an
 * arbitrary (non-hex) operator-supplied salt, encrypting the same value twice must never produce
 * the same ciphertext (a random IV per call, unlike the retired scheme), and a tampered ciphertext
 * must fail to decrypt rather than silently return garbage (GCM's authentication tag).
 */
class AESAlgorithmTest {

  private static final String SECRET = "a-test-secret";
  private static final String SALT = "salt";

  @Test
  void encryptThenDecryptRoundTrips() {
    String ciphertext = AESAlgorithm.encrypt("a secret setting value", SECRET, SALT);

    assertThat(ciphertext).isNotNull().isNotEqualTo("a secret setting value");
    assertThat(AESAlgorithm.decrypt(ciphertext, SECRET, SALT)).isEqualTo("a secret setting value");
  }

  @Test
  void encryptingTheSameValueTwiceProducesDifferentCiphertext() {
    String first = AESAlgorithm.encrypt("same value", SECRET, SALT);
    String second = AESAlgorithm.encrypt("same value", SECRET, SALT);

    assertThat(first).isNotEqualTo(second);
    assertThat(AESAlgorithm.decrypt(first, SECRET, SALT)).isEqualTo("same value");
    assertThat(AESAlgorithm.decrypt(second, SECRET, SALT)).isEqualTo("same value");
  }

  @Test
  void decryptingWithTheWrongSecretFails() {
    String ciphertext = AESAlgorithm.encrypt("a secret setting value", SECRET, SALT);

    assertThat(AESAlgorithm.decrypt(ciphertext, "a-different-secret", SALT)).isNull();
  }

  @Test
  void aTamperedCiphertextFailsToDecrypt() {
    String ciphertext = AESAlgorithm.encrypt("a secret setting value", SECRET, SALT);
    // Flip the last hex character - GCM's authentication tag must reject this rather than
    // returning corrupted plaintext.
    char lastChar = ciphertext.charAt(ciphertext.length() - 1);
    char flipped = lastChar == '0' ? '1' : '0';
    String tampered = ciphertext.substring(0, ciphertext.length() - 1) + flipped;

    assertThat(AESAlgorithm.decrypt(tampered, SECRET, SALT)).isNull();
  }

  @Test
  void anArbitraryNonHexSaltStillWorks() {
    // EncryptionConfig's configured salt is an arbitrary operator string, not necessarily valid
    // hex - AESAlgorithm must hex-encode it before handing it to the encoder.
    String ciphertext = AESAlgorithm.encrypt("value", SECRET, "not-hex-zzz!!");

    assertThat(AESAlgorithm.decrypt(ciphertext, SECRET, "not-hex-zzz!!")).isEqualTo("value");
  }
}
