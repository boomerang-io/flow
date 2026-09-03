package io.boomerang.core.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as an audited operation. {@link AuditAspect} wraps the method and
 * records ONE audit event per attempt with outcome SUCCESS, FAILED, or DENIED — the method's
 * exception (if any) is always rethrown, and the write is async best-effort (never fails the call).
 *
 * <p><b>Rules:</b>
 *
 * <ul>
 *   <li><b>Annotate at controller level only.</b> Spring AOP proxies don't intercept
 *       self-invocation, and annotating both a controller and the service it calls double-emits.
 *   <li>SpEL attributes are evaluated against the method arguments by name (e.g. {@code
 *       "#workspace"}) plus a {@code #result} variable holding the return value. {@code #result} is
 *       {@code null} on FAILED/DENIED — expressions using it must be null-safe ({@code
 *       "#result?.getName()"}). Prefer resolving ids from arguments.
 *   <li>A failing expression logs a WARN and yields null; it never breaks the request.
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

  /** What the actor did. */
  AuditAction action();

  /** Affected entity type, lowercase ("workflow", "workspace", "token"). Literal. */
  String resourceType();

  /** SpEL for the affected entity id or slug, e.g. {@code "#name"}. Empty = none. */
  String resourceId() default "";

  /** SpEL for a display name, e.g. {@code "#result?.getName()"}. Empty = none. */
  String resourceName() default "";

  /** SpEL for the owning workspace, e.g. {@code "#workspace"}. Empty = instance-scoped event. */
  String workspaceId() default "";

  /** Minimum configured {@code audit.level} at which this site fires. */
  AuditLevel level() default AuditLevel.WRITE;
}
