package io.boomerang.engine.repository;

import io.boomerang.engine.entity.TaskLockEntity;
import java.util.Date;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class TaskLockRepositoryCustomImpl implements TaskLockRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  public TaskLockRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public TaskLockEntity tryAcquire(
      String scopedKey, String taskRunRef, String workflowRunRef, Date expiresAt) {
    Date now = new Date();
    // Upsert only matches an unheld or expired key, so a live lock held by another task is never
    // stolen. When the key is held live the upsert tries to insert a duplicate _id and the unique
    // key throws - that is the "not acquired" signal.
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(scopedKey)
                .orOperator(
                    Criteria.where("expiresAt").exists(false),
                    Criteria.where("expiresAt").lte(now)));
    Update update =
        new Update()
            .set("holder", taskRunRef)
            .set("workflowRunRef", workflowRunRef)
            .set("acquiredAt", now)
            .set("expiresAt", expiresAt);
    try {
      TaskLockEntity lock =
          mongoTemplate.findAndModify(
              query,
              update,
              FindAndModifyOptions.options().upsert(true).returnNew(true),
              TaskLockEntity.class);
      return (lock != null && taskRunRef.equals(lock.getHolder())) ? lock : null;
    } catch (DuplicateKeyException held) {
      return null;
    }
  }

  @Override
  public void release(String scopedKey, String taskRunRef) {
    mongoTemplate.remove(
        Query.query(Criteria.where("_id").is(scopedKey).and("holder").is(taskRunRef)),
        TaskLockEntity.class);
  }
}
