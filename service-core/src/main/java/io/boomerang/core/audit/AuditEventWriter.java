package io.boomerang.core.audit;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Persists audit records off the request path. A separate bean from {@link AuditEventEmitter} so
 * the {@code @Async} proxy always applies (no self-invocation). Best-effort — a failed write logs
 * a WARN and never surfaces to the caller.
 */
@Service
public class AuditEventWriter {

  private static final Logger LOGGER = LogManager.getLogger();

  private final AuditEventRepository auditEventRepository;

  public AuditEventWriter(AuditEventRepository auditEventRepository) {
    this.auditEventRepository = auditEventRepository;
  }

  @Async("asyncTaskExecutor")
  public void persist(AuditRecord record) {
    try {
      auditEventRepository.save(toEntity(record));
    } catch (RuntimeException e) {
      LOGGER.warn(
          "Failed to write audit event {} {}={} outcome={}: {}",
          record.action(),
          record.resourceType(),
          record.resourceId(),
          record.outcome(),
          e.toString());
    }
  }

  static AuditEventEntity toEntity(AuditRecord record) {
    AuditEventEntity event = new AuditEventEntity();
    event.setTime(new Date());
    event.setSubject(record.resourceId());
    AuditActor actor = record.actor();
    if (actor != null) {
      event.setActorId(actor.id());
      event.setActorName(actor.name() != null ? actor.name() : actor.id());
      event.setActorType(actor.type());
    }
    event.setWorkspaceId(record.workspaceId());
    event.setAction(record.action().name());
    event.setResourceType(record.resourceType());
    event.setResourceId(record.resourceId());
    event.setResourceName(record.resourceName());
    event.setOutcome(record.outcome().name());
    event.setLevel(record.level().name());

    Map<String, Object> payload = new HashMap<>();
    putIfPresent(payload, "sourceIp", record.sourceIp());
    putIfPresent(payload, "userAgent", record.userAgent());
    putIfPresent(payload, "httpMethod", record.httpMethod());
    putIfPresent(payload, "requestPath", record.requestPath());
    putIfPresent(payload, "durationMs", record.durationMs());
    putIfPresent(payload, "errorSummary", record.errorSummary());
    putIfPresent(payload, "detail", record.detail());
    if (record.payload() != null) {
      payload.putAll(record.payload());
    }
    event.setPayload(payload);
    return event;
  }

  private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
      payload.put(key, value);
    }
  }
}
