package io.boomerang.engine;

import com.github.alturkovic.lock.exception.LockNotAvailableException;
import com.github.alturkovic.lock.mongo.impl.SimpleMongoLock;
import io.boomerang.config.MongoConfiguration;
import io.boomerang.util.CosmosDBMongoLock;
import io.boomerang.util.FlowMongoLock;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
public class LockManager {

  private static final Logger LOGGER = LogManager.getLogger(LockManager.class);

  private final String LOCKS_COLLECTION_NAME = "locks";

  @Value("${flow.mongo.cosmosdbttl}")
  private boolean mongoCosmosDBTTL;

  private final MongoTemplate mongoTemplate;
  private final MongoConfiguration mongoConfiguration;

  public LockManager(MongoTemplate mongoTemplate, MongoConfiguration mongoConfiguration) {
    this.mongoTemplate = mongoTemplate;
    this.mongoConfiguration = mongoConfiguration;
  }

  /*
   * Locks on a single key in 2 minutes timeout with 2 second backoff.
   */
  public String acquireLock(String key) {
    List<String> keys = new LinkedList<>();
    keys.add(key);
    return acquireLock(keys, 120000, 2000l, 100);
  }

  /*
   * Lock used by the AcquireTask lock
   *
   * Allows a custom key and timeout. Backoff period is 5s
   */
  public String acquireLock(String key, Long timeout) {
    final List<String> keys = new LinkedList<>();
    keys.add(key);

    return this.acquireLock(keys, timeout, 5000l, Integer.MAX_VALUE);
  }

  /*
   * Release Lock
   */
  public void releaseLock(String key, String tokenId) {
    List<String> keys = new LinkedList<>();
    keys.add(key);
    this.releaseLock(keys, tokenId);
  }

  /*
   * Utilize the Distributed Lock library to acquire (and retry endlessly)
   *
   * - The RetriableLock requires a TTL index on the expiresAt field in the MongoCollection. This is
   * created by the Loader, if you do not use the loader, you will need to create manually.
   *
   * - Extended the SimpleMongoLock for Azure CosmosDB API for Mongo. This uses the timeout in
   * seconds.
   *
   * - Under the covers, the token is created from the String in the supplier which we set it to the
   * key.
   *
   */
  // private String acquireLock(List<String> keys, long timeout, long backOffPeriod,
  // Integer maxAttempts) {
  // String storeId = mongoConfiguration.fullCollectionName(LOCKS_COLLECTION_NAME);
  // String token = "";
  //
  // if (keys != null) {
  // // SimpleMongoLock requires a supplier - don't want the value to change at a future assign
  // // time.
  // final String finalKey = keys.get(0);
  // Supplier<String> supplier = () -> finalKey;
  // RetryTemplate retryTemplate = getRetryTemplate(backOffPeriod, maxAttempts);
  // RetriableLock retryLock;
  // if (mongoCosmosDBTTL) {
  // CosmosDBMongoLock mongoLock = new CosmosDBMongoLock(supplier, this.mongoTemplate);
  // retryLock = new RetriableLock(mongoLock, retryTemplate);
  // token = retryLock.acquire(keys, storeId, timeout / 1000);
  // } else {
  // SimpleMongoLock mongoLock = new SimpleMongoLock(supplier, this.mongoTemplate);
  // retryLock = new RetriableLock(mongoLock, retryTemplate);
  // token = retryLock.acquire(keys, storeId, timeout);
  // }
  // }
  // LOGGER.debug("Token: " + token);
  // if (StringUtils.isEmpty(token)) {
  // /** TODO: What to do here. */
  // throw new LockNotAvailableException(
  // String.format("Lock not available for keys: %s in store %s", keys, storeId));
  // }
  // return token;
  // }

  // TODO - perform testing of the locks and if the above commented out code is needed
  private String acquireLock(
      List<String> keys, long timeout, long backOffPeriod, Integer maxAttempts) {
    String storeId = mongoConfiguration.fullCollectionName(LOCKS_COLLECTION_NAME);
    // String token = "";
    if (keys != null) {
      // SimpleMongoLock requires a supplier - don't want the value to change at a future assign
      // time.
      final String finalKey = keys.get(0);
      Supplier<String> supplier = () -> finalKey;
      // RetriableLock retryLock;
      RetryTemplate retryTemplate = getRetryTemplate(backOffPeriod, maxAttempts);
      if (mongoCosmosDBTTL) {
        CosmosDBMongoLock mongoLock = new CosmosDBMongoLock(supplier, this.mongoTemplate);
        // retryLock = new RetriableLock(mongoLock, retryTemplate);
        // token = retryLock.acquire(keys, storeId, timeout / 1000);

        return retryTemplate.execute(
            ctx -> {
              final boolean lockExists = mongoLock.exists(storeId, keys.get(0));
              if (lockExists) {
                throw new LockNotAvailableException(
                    String.format(
                        "Lock hasn't been released yet for: %s in store %s", keys, storeId));
              }
              return mongoLock.acquire(keys, storeId, timeout);
            });
      } else {
        FlowMongoLock mongoLock = new FlowMongoLock(supplier, this.mongoTemplate);
        // retryLock = new RetriableLock(mongoLock, retryTemplate);

        // if (StringUtils.isEmpty(token)) {
        // /** TODO: What to do here. */
        // throw new LockNotAvailableException(
        // String.format("Lock not available for keys: %s in store %s", keys, storeId));
        // }
        // return retryTemplate.execute(ctx -> {
        // final String token = mongoLock.acquire(keys, storeId, timeout);
        // if (StringUtils.isEmpty(token)) {
        // throw new LockNotAvailableException(
        // String.format("Lock not available for keys: %s in store %s", keys, storeId));
        // }
        // return token;
        // });

        return retryTemplate.execute(
            ctx -> {
              final boolean lockExists = mongoLock.exists(storeId, keys.get(0));
              if (lockExists) {
                throw new LockNotAvailableException(
                    String.format(
                        "Lock hasn't been released yet for: %s in store %s", keys, storeId));
              }
              return mongoLock.acquire(keys, storeId, timeout);
            });
      }
    }
    // LOGGER.debug("Token: " + token);
    // if (StringUtils.isEmpty(token)) {
    // /** TODO: What to do here. */
    // throw new LockNotAvailableException(
    // String.format("Lock not available for keys: %s in store %s", keys, storeId));
    // }
    return "";
  }

  private RetryTemplate getRetryTemplate(long backOffPeriod, Integer maxAttempts) {
    RetryTemplate retryTemplate = new RetryTemplate();
    FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(backOffPeriod);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(maxAttempts);
    retryTemplate.setRetryPolicy(retryPolicy);
    return retryTemplate;
  }

  /*
   * Releases the lock
   *
   * - Under the covers, the token is the same as the key due to the suplier
   */
  private void releaseLock(List<String> keys, String tokenId) {
    String storeId = mongoConfiguration.fullCollectionName(LOCKS_COLLECTION_NAME);

    if (keys != null) {
      final String finalKey = keys.get(0);
      Supplier<String> supplier = () -> finalKey;
      if (mongoCosmosDBTTL) {
        CosmosDBMongoLock mongoLock = new CosmosDBMongoLock(supplier, this.mongoTemplate);
        mongoLock.release(keys, storeId, tokenId);
      } else {
        SimpleMongoLock mongoLock = new SimpleMongoLock(supplier, this.mongoTemplate);
        mongoLock.release(keys, storeId, tokenId);
      }
    }
  }
}
