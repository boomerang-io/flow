package io.boomerang.core.model;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Data
@Configuration
@PropertySource("classpath:application.properties")
public class EncryptionConfig {

  @Value("${mongo.encrypt.secret:secret}")
  private String secretKey;

  @Value("${mongo.encrypt.salt:salt}")
  private String salt;
}
