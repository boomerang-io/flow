package io.boomerang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@SpringBootApplication
@EnableAutoConfiguration
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public OpenAPI api() {
    return new OpenAPI();
  }
}
