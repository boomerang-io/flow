package io.boomerang.engine.repository;

import io.boomerang.engine.entity.EventOutboxEntity;
import io.boomerang.engine.enums.OutboxStatus;
import java.util.Date;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class EventOutboxRepositoryCustomImpl implements EventOutboxRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  public EventOutboxRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public List<EventOutboxEntity> findDeliverable(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(OutboxStatus.pending)
                    .orOperator(
                        Criteria.where("retry.after").exists(false),
                        Criteria.where("retry.after").lte(now)))
            .with(Sort.by(Sort.Direction.ASC, "occurredAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, EventOutboxEntity.class);
  }

  @Override
  public EventOutboxEntity tryMarkSent(String id, Date sentAt) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update().set("status", OutboxStatus.sent).set("sentAt", sentAt).unset("retry.after"),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  @Override
  public EventOutboxEntity tryRequeueDelivery(String id, Date retryAfter, int attempts) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update().set("retry.after", retryAfter).set("attempts", attempts),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  @Override
  public EventOutboxEntity tryMarkDead(String id) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update().set("status", OutboxStatus.dead).unset("retry.after"),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  private static Query pendingById(String id) {
    return Query.query(Criteria.where("_id").is(id).and("status").is(OutboxStatus.pending));
  }
}
