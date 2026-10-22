package io.boomerang.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration
public class MongoConfiguration {

  @Value("${flow.mongo.collection.prefix}")
  private String collectionPrefix;

  public String fullCollectionName(String collectionName) {

    if (collectionPrefix == null || collectionPrefix.isBlank()) {
      return "" + collectionName;
    }

    collectionPrefix = collectionPrefix.endsWith("_") ? collectionPrefix : collectionPrefix + "_";
    return collectionPrefix + collectionName;
  }

  public String collectionPrefix() {
    return this.collectionPrefix;
  }

  @Autowired
  public void setMapKeyDotReplacement(MappingMongoConverter mongoConverter) {
    mongoConverter.setMapKeyDotReplacement("#");
  }
}
