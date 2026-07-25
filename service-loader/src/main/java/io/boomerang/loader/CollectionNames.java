package io.boomerang.loader;

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

  public String resolve(String collectionName) {
    return prefix + collectionName;
  }
}
