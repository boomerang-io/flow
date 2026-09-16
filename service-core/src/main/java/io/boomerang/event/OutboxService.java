package io.boomerang.event;

import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.OutboxStatus;
import java.util.Date;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/**
 * Operator view of the events outbox. A row that exhausts its delivery attempts is kept as dead
 * rather than dropped, and until now the only way back was editing the collection by hand: this
 * lists dead rows and puts them back in the queue, which the next {@link OutboxDispatcher} pass
 * picks up. Reading and replaying are independent of the sink being enabled - an operator has to
 * be able to see and requeue rows a disabled sink left behind.
 */
@Service
public class OutboxService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final MongoTemplate mongoTemplate;

  public OutboxService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public Page<EventOutboxEntity> query(OutboxStatus status, int page, int limit) {
    Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "occurredAt"));
    Query query = Query.query(Criteria.where("status").is(status)).with(pageable);
    List<EventOutboxEntity> rows = mongoTemplate.find(query, EventOutboxEntity.class);
    return PageableExecutionUtils.getPage(
        rows,
        pageable,
        () ->
            mongoTemplate.count(
                Query.of(query).skip(-1).limit(-1), EventOutboxEntity.class));
  }

  /**
   * Requeue rows for delivery: status back to pending, the backoff cleared to now and the attempt
   * count reset so the dispatcher gives them the full retry budget again. Named rows are replayed
   * whatever their status; with no ids, every row in {@code status} (optionally only those that
   * occurred before {@code olderThan}) is replayed. Returns how many rows changed.
   */
  public long replay(List<String> ids, OutboxStatus status, Date olderThan) {
    Criteria criteria =
        (ids != null && !ids.isEmpty())
            ? Criteria.where("_id").in(ids)
            : Criteria.where("status").is(status);
    if (olderThan != null) {
      criteria = criteria.and("occurredAt").lt(olderThan);
    }
    Date now = new Date();
    long replayed =
        mongoTemplate
            .updateMulti(
                Query.query(criteria),
                new Update()
                    .set("status", OutboxStatus.pending)
                    .set("attempts", 0)
                    .set("retry.after", now)
                    .unset("sentAt"),
                EventOutboxEntity.class)
            .getModifiedCount();
    LOGGER.info("Outbox replay requeued {} row(s).", replayed);
    return replayed;
  }
}
