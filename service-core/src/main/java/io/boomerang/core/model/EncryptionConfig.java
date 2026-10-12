package io.boomerang.core.model;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.util.StringUtils;

/**
 * The key every secret at rest is encrypted with. It is required on every install; the v3 pair
 * under {@code mongo.encrypt.*} is read only by the migration that re-encrypts v3's values.
 */
@Data
@Configuration
@PropertySource("classpath:application.properties")
public class EncryptionConfig {

  @Value("${flow.encrypt.secret:}")
  private String secretKey;

  @Value("${flow.encrypt.salt:}")
  private String salt;

  @PostConstruct
  void requireKey() {
    if (!StringUtils.hasText(secretKey) || !StringUtils.hasText(salt)) {
      throw new IllegalStateException(
          "flow.encrypt.secret and flow.encrypt.salt must be set: they are the key secured"
              + " settings are encrypted with, and nothing is encrypted under a blank one");
    }
  }
}
