package io.boomerang.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for MongoDB to override out of the box health check. *
 *
 * <p>management.health.mongo.enabled=false
 */
@Component
public class MongoHealthConfiguration implements HealthIndicator {

  private final MongoTemplate mongoTemplate;

  public MongoHealthConfiguration(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public Health health() {
    try {
      mongoTemplate.executeCommand("{ ping: 1 }");
      return Health.up().build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
