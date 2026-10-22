package io.boomerang.util;

import java.util.function.Supplier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import com.github.alturkovic.lock.mongo.impl.SimpleMongoLock;
import com.github.alturkovic.lock.mongo.model.LockDocument;

public class FlowMongoLock extends SimpleMongoLock {

  private MongoTemplate mongoTemplate;
  
  public FlowMongoLock(Supplier<String> tokenSupplier, MongoTemplate mongoTemplate) {
    super(tokenSupplier, mongoTemplate);
    this.mongoTemplate = mongoTemplate;
  }
  
  public boolean exists(final String storeId, final String token) {
    final var query = Query.query(Criteria.where("token").is(token)); 
    return mongoTemplate.exists(query, LockDocument.class, storeId);
  }
  
  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }
  
  @Override
  public int hashCode()
  {
    return super.hashCode();
  }
}
