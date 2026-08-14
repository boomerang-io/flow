package io.boomerang.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.stereotype.Component;

/**
 * This class is used to configure the MongoDB connection.
 *
 * <p>Referenced by all entity classes to get the collection name via the class name (WARNING)
 */
@Component
public class MongoConfiguration {

  @Value("${flow.mongo.collection.prefix}")
  private String workflowCollectionPrefix;

  public String fullCollectionName(String collectionName) {

    if (workflowCollectionPrefix == null || workflowCollectionPrefix.isBlank()) {
      return "" + collectionName;
    }
    workflowCollectionPrefix =
        workflowCollectionPrefix.endsWith("_")
            ? workflowCollectionPrefix
            : workflowCollectionPrefix + "_";
    return workflowCollectionPrefix + collectionName;
  }

  public String collectionPrefix() {
    return this.workflowCollectionPrefix;
  }

  // Merged from service-engine (E8.2a)
  @Autowired
  public void setMapKeyDotReplacement(MappingMongoConverter mongoConverter) {
    mongoConverter.setMapKeyDotReplacement("#");
  }
}
