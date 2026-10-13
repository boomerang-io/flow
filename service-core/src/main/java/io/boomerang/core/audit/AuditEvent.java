package io.boomerang.core.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;
import java.util.Map;

/**
 * The public shape of one audited attempt, returned by {@link AuditControllerV2}. Carries the
 * fields a reader of the trail needs and nothing the store needs for itself: the CloudEvents
 * envelope ({@code type}, {@code source}, {@code subject}) and the TTL anchor {@code createdAt}
 * stay on {@link AuditEventEntity}.
 *
 * <p>{@code payload} is passed through as captured. It holds request discriminators only - source
 * IP, user agent, HTTP method and path, duration, an error summary, and any ids a call site
 * added - never request or response content ({@link AuditEventWriter#toEntity}).
 */
@JsonInclude(Include.NON_NULL)
public record AuditEvent(
    String id,
    Date time,
    String actorId,
    String actorName,
    String actorType,
    String workspaceId,
    String action,
    String resourceType,
    String resourceId,
    String resourceName,
    String outcome,
    String level,
    Map<String, Object> payload) {

  public static AuditEvent from(AuditEventEntity entity) {
    return new AuditEvent(
        entity.getId(),
        entity.getTime(),
        entity.getActorId(),
        entity.getActorName(),
        entity.getActorType(),
        entity.getWorkspaceId(),
        entity.getAction(),
        entity.getResourceType(),
        entity.getResourceId(),
        entity.getResourceName(),
        entity.getOutcome(),
        entity.getLevel(),
        entity.getPayload());
  }
}
