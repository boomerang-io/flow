package io.boomerang.workflow.model;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class AESAlgorithm {

  private static final Logger LOGGER = Logger.getLogger(AESAlgorithm.class.getName());

  private static final int PWD_ITERATOINS = 131072;
  private static final int KEY_SIZE = 256;
  private static final byte[] IV =
      {11, 112, 13, 117, 45, 68, 17, -55, -6, 77, 10, -13, -78, 4, -127, -61};

  private static final String KEY_ALGORITHM = "AES";
  private static final String ENCRYPT_ALGORITHM = "AES/CBC/PKCS5Padding";
  private static final String SECRET_KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA1";

  private AESAlgorithm() {
    // Do nothing
  }

  public static String encrypt(String strToEncrypt, String secret, String salt) {
    try {
      SecretKeySpec secretKey = getSecretKeySpec(secret, salt.getBytes(StandardCharsets.UTF_8));

      // AES initialization
      Cipher cipher = Cipher.getInstance(ENCRYPT_ALGORITHM); //NOSONAR
      IvParameterSpec ivSpec = new IvParameterSpec(IV);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

      return Base64.getEncoder()
          .encodeToString(cipher.doFinal(strToEncrypt.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      LOGGER.log(Level.SEVERE, "Error encrypt value: ", e);
    }

    return null;
  }

  public static String decrypt(String strToDecrypt, String secret, String salt) {
    try {
      SecretKeySpec secretKey = getSecretKeySpec(secret, salt.getBytes(StandardCharsets.UTF_8));

      Cipher cipher = Cipher.getInstance(ENCRYPT_ALGORITHM);  //NOSONAR
      IvParameterSpec ivSpec = new IvParameterSpec(IV);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

      byte[] byteToDecrypt = Base64.getDecoder().decode(strToDecrypt);
      return new String(cipher.doFinal(byteToDecrypt));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      LOGGER.log(Level.SEVERE, "Error decrypt value: ", e);
    }

    return null;
  }

  private static SecretKeySpec getSecretKeySpec(String secret, byte[] saltBytes)
      throws GeneralSecurityException {

    SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_FACTORY_ALGORITHM);
    PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), saltBytes, PWD_ITERATOINS, KEY_SIZE);
    return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), KEY_ALGORITHM);
  }
}
