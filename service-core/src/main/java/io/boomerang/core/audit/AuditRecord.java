package io.boomerang.core.audit;

import java.util.Map;

/**
 * Immutable capture of one audited attempt, assembled synchronously on the calling thread (the
 * SecurityContext and RequestContextHolder thread-locals must not cross into the async writer) and
 * handed to {@link AuditEventWriter#persist(AuditRecord)}.
 *
 * <p>{@code detail} names what the call changed without its content, such as the secrets a write
 * changed or cleared ({@link SecretChangeHolder}); null when there is nothing to say. {@code payload}
 * carries caller-supplied discriminators (ids, short summaries — never content) merged into the
 * event's payload after the request fields; null when the site has none.
 */
public record AuditRecord(
    AuditActor actor,
    String workspaceId,
    AuditAction action,
    AuditLevel level,
    String resourceType,
    String resourceId,
    String resourceName,
    AuditOutcome outcome,
    String sourceIp,
    String userAgent,
    String httpMethod,
    String requestPath,
    Long durationMs,
    String errorSummary,
    Map<String, Object> detail,
    Map<String, Object> payload) {}
