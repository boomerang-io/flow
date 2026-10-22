//package io.boomerang.service;
//
//import java.util.LinkedList;
//import java.util.List;
//import java.util.function.Supplier;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.commons.lang3.math.NumberUtils;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.mongodb.core.MongoTemplate;
//import org.springframework.retry.backoff.FixedBackOffPolicy;
//import org.springframework.retry.policy.SimpleRetryPolicy;
//import org.springframework.retry.support.RetryTemplate;
//import org.springframework.stereotype.Service;
//import com.github.alturkovic.lock.exception.LockNotAvailableException;
//import com.github.alturkovic.lock.mongo.impl.SimpleMongoLock;
//import com.github.alturkovic.lock.retry.RetriableLock;
//import io.boomerang.config.MongoConfiguration;
//import io.boomerang.data.entity.TaskRunEntity;
//import io.boomerang.model.RunParam;
//import io.boomerang.util.CosmosDBMongoLock;
//import io.boomerang.util.FlowMongoLock;
//import io.boomerang.util.ParameterUtil;
//
//@Service
//public class LockManagerNew {
//
//  @Value("${flow.mongo.cosmosdbttl}")
//  private boolean mongoCosmosDBTTL;
//
//  private final String LOCKS_COLLECTION_NAME = "locks";
//
//  @Autowired
//  private MongoTemplate mongoTemplate;
//
//  @Autowired
//  private MongoConfiguration mongoConfiguration;
//
//  private static final Logger LOGGER = LogManager.getLogger(LockManagerImplNew.class);
//
//  /*
//   * Locks on a single key in 2 minutes timeout
//   */
//  public String acquireRunLock(String key) {
//    List<String> keys = new LinkedList<>();
//    keys.add(key);
//    return acquireLock(keys, 120000, 2000l, 100);
//  }
//
//  @Override
//  // TODO add a prefix for the owning user or team, otherwise the lock is essentially system wide.
//  public String acquireTaskLock(TaskRunEntity taskExecution, String wfRunId) {
//    long timeout = 60000;
//    String key = null;
//
//    if (taskExecution != null) {
//      List<RunParam> params = taskExecution.getParams();
//      if (ParameterUtil.containsName(params, "timeout")) {
//        String timeoutStr = ParameterUtil.getValue(params, "timeout").toString();
//        if (!timeoutStr.isBlank() && NumberUtils.isCreatable(timeoutStr)) {
//          timeout = Long.valueOf(timeoutStr);
//        }
//      }
//
//      if (ParameterUtil.containsName(params, "key")) {
//        key = ParameterUtil.getValue(params, "key").toString();
//      }
//      final List<String> keys = new LinkedList<>();
//      keys.add(key);
//
//      return this.acquireLock(keys, timeout, 10000l, Integer.MAX_VALUE);
//
//    } else {
//      // TODO update with failure so that the Task can fail.
//      LOGGER.info("No Acquire Lock Key Found!");
//      return null;
//    }
//  }
//
//  /*
//   * Utilize the Distributed Lock library to acquire (and retry endlessly)
//   * 
//   * - The RetriableLock requires a TTL index on the expiresAt field in the MongoCollection. This is
//   * created by the Loader, if you do not use the loader, you will need to create manually.
//   * 
//   * - Extended the SimpleMongoLock for Azure CosmosDB API for Mongo. This uses the timeout in
//   * seconds.
//   * 
//   * - Under the covers, the token is created from the String in the supplier which we set it to the
//   * key.
//   * 
//   */
//  private String acquireLock(List<String> keys, long timeout, long backOffPeriod,
//      Integer maxAttempts) {
//    String storeId = mongoConfiguration.fullCollectionName(LOCKS_COLLECTION_NAME);
//    String token = "";
//    if (keys != null) {
//      // SimpleMongoLock requires a supplier - don't want the value to change at a future assign
//      // time.
//      final String finalKey = keys.get(0);
//      Supplier<String> supplier = () -> finalKey;
//      RetriableLock retryLock;
//      if (mongoCosmosDBTTL) {
//        CosmosDBMongoLock mongoLock = new CosmosDBMongoLock(supplier, this.mongoTemplate);
////        retryLock = new RetriableLock(mongoLock, retryTemplate);
////        token = retryLock.acquire(keys, storeId, timeout / 1000);
//        return "";
//      } else {
//        FlowMongoLock mongoLock = new FlowMongoLock(supplier, this.mongoTemplate);
////        retryLock = new RetriableLock(mongoLock, retryTemplate);
//
////        if (StringUtils.isEmpty(token)) {
////          /** TODO: What to do here. */
////          throw new LockNotAvailableException(
////              String.format("Lock not available for keys: %s in store %s", keys, storeId));
////        }
//
//        RetryTemplate retryTemplate = getRetryTemplate(backOffPeriod, maxAttempts);
//        retryTemplate.execute(ctx -> {
//          final boolean lockExists = mongoLock.exists(storeId, keys.get(0));
//          if (!lockExists) {
//            return mongoLock.acquire(keys, storeId, timeout);
//          } else {
//            throw new LockNotAvailableException(
//                String.format("Lock hasn't been released yet for: %s in store %s", keys, storeId));
//          }
//        });
//      }
//    }
////    LOGGER.debug("Token: " + token);
////    if (StringUtils.isEmpty(token)) {
////      /** TODO: What to do here. */
////      throw new LockNotAvailableException(
////          String.format("Lock not available for keys: %s in store %s", keys, storeId));
////    }
//    return "";
//  }
//
//  private RetryTemplate getRetryTemplate(long backOffPeriod, Integer maxAttempts) {
//    RetryTemplate retryTemplate = new RetryTemplate();
//    FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
//    fixedBackOffPolicy.setBackOffPeriod(backOffPeriod);
//    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
//    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
//    retryPolicy.setMaxAttempts(maxAttempts);
//    retryTemplate.setRetryPolicy(retryPolicy);
//    return retryTemplate;
//  }
//
//  @Override
//  public void releaseRunLock(String key, String tokenId) {
//    List<String> keys = new LinkedList<>();
//    keys.add(key);
//    this.releaseLock(keys, tokenId);
//  }
//
//  @Override
//  public void releaseTaskLock(TaskRunEntity taskExecution, String wfRunId) {
//    String key = null;
//    if (taskExecution != null) {
//      List<RunParam> params = taskExecution.getParams();
//      if (ParameterUtil.containsName(params, "key")) {
//        key = ParameterUtil.getValue(params, "key").toString();
//      }
//
//      if (key != null) {
//        final List<String> keys = new LinkedList<>();
//        keys.add(key);
//        this.releaseLock(keys, key);
//      }
//    }
//  }
//
//  /*
//   * Releases the lock
//   * 
//   * - Under the covers, the token is the same as the key due to the suplier
//   */
//  private void releaseLock(List<String> keys, String tokenId) {
//    String storeId = mongoConfiguration.fullCollectionName(LOCKS_COLLECTION_NAME);
//
//    if (keys != null) {
//      final String finalKey = keys.get(0);
//      Supplier<String> supplier = () -> finalKey;
//      if (mongoCosmosDBTTL) {
//        CosmosDBMongoLock mongoLock = new CosmosDBMongoLock(supplier, this.mongoTemplate);
//        mongoLock.release(keys, storeId, tokenId);
//      } else {
//        SimpleMongoLock mongoLock = new SimpleMongoLock(supplier, this.mongoTemplate);
//        mongoLock.release(keys, storeId, tokenId);
//      }
//    }
//  }
//}
