package io.boomerang.core.audit;

/**
 * Immutable capture of one audited attempt, assembled synchronously on the calling thread (the
 * SecurityContext and RequestContextHolder thread-locals must not cross into the async writer) and
 * handed to {@link AuditEventWriter#persist(AuditRecord)}.
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
    String detail) {}
