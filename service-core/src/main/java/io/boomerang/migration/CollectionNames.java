package io.boomerang.migration;

import org.springframework.core.env.Environment;

/**
 * Resolve full collection names with the configured {@code flow.mongo.collection.prefix},
 * matching the services' {@code MongoConfiguration.fullCollectionName} rule: a blank prefix
 * yields the bare name; otherwise the prefix is applied with a single trailing underscore
 * (e.g. prefix {@code flow} → {@code flow_task_runs}).
 */
public class CollectionNames {

  private final String prefix;

  public CollectionNames(String prefix) {
    this.prefix =
        (prefix == null || prefix.isBlank())
            ? ""
            : (prefix.endsWith("_") ? prefix : prefix + "_");
  }

  /**
   * The names for {@code flow.mongo.collection.prefix}. Change units take the {@link Environment},
   * a library type, because devtools' restart classloader breaks injection of the project's own.
   */
  public static CollectionNames from(Environment environment) {
    return new CollectionNames(environment.getProperty("flow.mongo.collection.prefix"));
  }

  public String resolve(String collectionName) {
    return prefix + collectionName;
  }
}
