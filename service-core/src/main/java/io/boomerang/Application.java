package io.boomerang;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.bind.annotation.RestController;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Boomerang Flow",
            version = "4.0.0",
            description = "Cloud-native Workflow automation"))
@SecuritySchemes({
  @SecurityScheme(
      name = "BearerAuth",
      type = SecuritySchemeType.HTTP,
      scheme = "bearer",
      bearerFormat = "JWT"),
  @SecurityScheme(
      name = "x-access-token",
      type = SecuritySchemeType.APIKEY,
      in = SecuritySchemeIn.HEADER,
      paramName = "x-access-token")
})
@SpringBootApplication
@EnableWebSecurity
@RestController
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(io.boomerang.Application.class, args);
  }

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
  // TODO: figure out if needed
  //  @Bean
  //  public OpenAPI api() {
  //    return new OpenAPI();
  //  }
}
