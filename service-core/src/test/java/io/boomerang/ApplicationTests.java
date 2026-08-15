package io.boomerang;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import org.junit.jupiter.api.Test;

/*
 * Extends AbstractEngineIntegrationTest so the context boots against the hermetic Testcontainers
 * MongoDB rather than requiring a Mongo on localhost:27017.
 */
class ApplicationTests extends AbstractEngineIntegrationTest {

  @Test
  void contextLoads() {}
}
