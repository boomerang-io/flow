package io.boomerang.service.refactor;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import com.github.alturkovic.lock.exception.LockNotAvailableException;

import io.boomerang.model.Task;
import io.boomerang.mongo.service.MongoConfiguration;
import io.boomerang.service.PropertyManager;
import io.boomerang.util.FlowMongoLock;

@Service
public class LockManagerImpl implements LockManager {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MongoConfiguration mongoConfiguration;

  @Autowired
  private PropertyManager propertyManager;

  private static final Logger LOGGER = LogManager.getLogger(LockManagerImpl.class);

  @Override
  public void acquireLock(Task taskExecution, String activityId) {


    long timeout = 7200000L;	// 2 hours timeout default
    String key = null;

    if (taskExecution != null) {
    	
    	LOGGER.info("taskExecution.taskActivityId: " + taskExecution.getTaskActivityId());
      
      String workflowId = taskExecution.getWorkflowId();

      Map<String, String> properties = taskExecution.getInputs();
      if (properties.get("timeout") != null) {
        String timeoutStr = properties.get("timeout");
        if (!timeoutStr.isBlank() && NumberUtils.isCreatable(timeoutStr)) {
          timeout = Long.valueOf(timeoutStr) * 1000L;
        }
      }
      
      if (properties.get("key") != null) {
        key = properties.get("key");
        ControllerRequestProperties propertiesList =
            propertyManager.buildRequestPropertyLayering(null, activityId, workflowId);
        key = propertyManager.replaceValueWithProperty(key, activityId, propertiesList);
      }      
      
      if (key != null) {      	
        final String test = key;
        Supplier<String> supplier = () -> test;
        String storeID = mongoConfiguration.fullCollectionName("tasks_locks");
        FlowMongoLock mongoLock = new FlowMongoLock(supplier, this.mongoTemplate);
        String storeId = key;
        final List<String> keys = new LinkedList<>();
        keys.add(storeId);
        
        LOGGER.info("Start acquire lock");
        final String token = mongoLock.acquire(keys, storeID, 7200000L);	// 2 hours expiration
        LOGGER.info("End acquire lock");

        if (StringUtils.isEmpty(token)) {
        	LOGGER.info("Lock not available for keys: " + keys + " in store: " + storeId);
        	
          RetryTemplate retryTemplate = getRetryTemplate(timeout);
          retryTemplate.execute(ctx -> {
            final boolean lockExists = mongoLock.exists(storeID, token);
            
            if (lockExists) {
            	LOGGER.info("Lock not available for keys: " + keys + " in store: " + storeId);
              throw new LockNotAvailableException(
                  String.format("Lock hasn't been released yet for: %s in store %s", keys, storeId));
            }
            return lockExists;
          });
        }
        else {
        	LOGGER.info("Lock acquired with token: " + token);	
        }
      } else {
        LOGGER.info("No Acquire Lock Key Found!");
      }
    }
  }

  private RetryTemplate getRetryTemplate(long timeout) {
    RetryTemplate retryTemplate = new RetryTemplate();
    FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(10000l);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(Integer.MAX_VALUE);
    retryTemplate.setRetryPolicy(retryPolicy);
    TimeoutRetryPolicy policy = new TimeoutRetryPolicy();
    policy.setTimeout(timeout);
    retryTemplate.setRetryPolicy(policy);
    return retryTemplate;
  }

  @Override
  public void releaseLock(Task taskExecution, String activityId) {
    String storeID = mongoConfiguration.fullCollectionName("tasks_locks");
    String key = null;
    if (taskExecution != null) {
      String workflowId = taskExecution.getWorkflowId();
      Map<String, String> properties = taskExecution.getInputs();
      if (properties.get("key") != null) {
        key = properties.get("key");
        ControllerRequestProperties propertiesList =
            propertyManager.buildRequestPropertyLayering(taskExecution, activityId, workflowId);
        key = propertyManager.replaceValueWithProperty(key, activityId, propertiesList);
      }
    }

    if (key != null) {
      String workflowId = taskExecution.getWorkflowId();
      ControllerRequestProperties properties =
          propertyManager.buildRequestPropertyLayering(null, activityId, workflowId);
      final String textValue =
          propertyManager.replaceValueWithProperty(key, activityId, properties);
      Supplier<String> supplier = () -> textValue;
      FlowMongoLock mongoLock = new FlowMongoLock(supplier, this.mongoTemplate);

      final List<String> keys = new LinkedList<>();
      keys.add(textValue);
      mongoLock.release(keys, storeID, textValue);
    }
  }
}
