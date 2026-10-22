package io.boomerang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import com.github.alturkovic.lock.mongo.configuration.EnableMongoDistributedLock;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.OpenAPI;

@SpringBootApplication(exclude = { SecurityAutoConfiguration.class })
@EnableMongoDistributedLock
@OpenAPIDefinition(info = @Info(title = "Boomerang Flow - Workflow Engine", version = "1.0.0", description = ""))
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}
	  
    @Bean
    public OpenAPI api() {
      return new OpenAPI();
    }
}
