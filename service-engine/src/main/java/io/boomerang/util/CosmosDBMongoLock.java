package io.boomerang.util;

import java.util.function.Supplier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.github.alturkovic.lock.mongo.impl.SimpleMongoLock;
import com.github.alturkovic.lock.mongo.model.LockDocument;

/*
 * Extension of the standard lock for Azure CosmosDB API for MongoDB
 * 
 * TTL is a specific limitation of CosmosDB and needs specific fields
 * 
 * Ref: https://learn.microsoft.com/en-us/azure/cosmos-db/mongodb/time-to-live
 */
public class CosmosDBMongoLock extends SimpleMongoLock {

  private MongoTemplate mongoTemplate;
  
  public CosmosDBMongoLock(Supplier<String> tokenSupplier, MongoTemplate mongoTemplate) {
    super(tokenSupplier, mongoTemplate);
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  protected String acquire(final String key, final String storeId, final String token, final long expiration) {
    final Query query = Query.query(Criteria.where("_id").is(key));
    final Update update = new Update()
      .setOnInsert("_id", key)
      .setOnInsert("ttl", expiration)
      .setOnInsert("token", token);

    final FindAndModifyOptions options = new FindAndModifyOptions().upsert(true).returnNew(true);
    final LockDocument doc = mongoTemplate.findAndModify(query, update, options, LockDocument.class, storeId);

    final boolean locked = doc.getToken().equals(token);
    return locked ? token : null;
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