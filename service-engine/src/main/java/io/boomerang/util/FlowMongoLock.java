package io.boomerang.util;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
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
    boolean lockExists = mongoTemplate.exists(query, LockDocument.class, storeId);
    if (lockExists) {
    	List<LockDocument> lockList = mongoTemplate.find(query, LockDocument.class, storeId);
    	for (LockDocument lock : lockList) {
    		if (lock.getExpireAt().isBefore(LocalDateTime.now())) {
    			final List<String> keys = new LinkedList<>();
          keys.add(token);
    			this.release(keys, storeId, token);
    		}
    	}
    	lockExists = mongoTemplate.exists(query, LockDocument.class, storeId);
    }
    return lockExists;
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
