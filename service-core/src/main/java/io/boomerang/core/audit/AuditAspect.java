package io.boomerang.core.audit;

import io.boomerang.common.error.BoomerangException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Records one audit event per {@link Audited} method attempt — SUCCESS, FAILED, or DENIED — and
 * always rethrows. Everything except the Mongo write happens synchronously on the request thread
 * (the SecurityContext and RequestContextHolder are thread-locals); the write goes async via
 * {@link AuditEventWriter}.
 *
 * <p>The disabled/level-gated path does no SpEL or context work — one settings read, then {@code
 * proceed()}.
 *
 * <p>{@code @Order(0)} keeps audit advice outside any other advice on the method so a downstream
 * failure records as FAILED.
 */
@Aspect
@Component
@Order(0)
public class AuditAspect {

  private static final Logger LOGGER = LogManager.getLogger();
  private static final int ERROR_SUMMARY_MAX = 300;
  private static final ParameterNameDiscoverer PARAMETER_NAMES =
      new DefaultParameterNameDiscoverer();

  private final AuditEventEmitter emitter;
  private final AuditEventWriter writer;

  private final SpelExpressionParser parser = new SpelExpressionParser();
  private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

  public AuditAspect(AuditEventEmitter emitter, AuditEventWriter writer) {
    this.emitter = emitter;
    this.writer = writer;
  }

  @Around("@annotation(audited)")
  public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
    if (!emitter.captureEnabled(audited.level())) {
      return pjp.proceed();
    }

    long start = System.nanoTime();
    try {
      Object result = pjp.proceed();
      emit(pjp, audited, result, null, AuditOutcome.SUCCESS, start);
      return result;
    } catch (Throwable e) {
      emit(pjp, audited, null, e, outcomeOf(e), start);
      throw e;
    }
  }

  /** A refused authentication or authorization is DENIED; any other failure is FAILED. */
  private static AuditOutcome outcomeOf(Throwable error) {
    if (error instanceof org.springframework.security.access.AccessDeniedException) {
      return AuditOutcome.DENIED;
    }
    if (error instanceof BoomerangException be
        && (be.getStatus() == HttpStatus.FORBIDDEN || be.getStatus() == HttpStatus.UNAUTHORIZED)) {
      return AuditOutcome.DENIED;
    }
    return AuditOutcome.FAILED;
  }

  /** Assemble the record on the request thread; only the write is async. */
  private void emit(
      ProceedingJoinPoint pjp,
      Audited audited,
      Object result,
      Throwable error,
      AuditOutcome outcome,
      long startNanos) {
    try {
      Method method = ((MethodSignature) pjp.getSignature()).getMethod();
      MethodBasedEvaluationContext context =
          new MethodBasedEvaluationContext(pjp.getTarget(), method, pjp.getArgs(), PARAMETER_NAMES);
      context.setVariable("result", result);

      AuditRequestContext http = AuditRequestContext.capture();
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

      writer.persist(
          new AuditRecord(
              emitter.currentActor(http),
              evaluate(audited.workspaceId(), context, method),
              audited.action(),
              audited.level(),
              audited.resourceType(),
              evaluate(audited.resourceId(), context, method),
              evaluate(audited.resourceName(), context, method),
              outcome,
              http.sourceIp(),
              http.userAgent(),
              http.method(),
              http.path(),
              durationMs,
              errorSummary(error),
              null));
    } catch (RuntimeException e) {
      // Audit assembly must never break the request - the outcome (including the original
      // exception) has already been decided by the caller.
      LOGGER.warn(
          "Failed to assemble audit record for {} {}: {}",
          audited.action(),
          audited.resourceType(),
          e.toString());
    }
  }

  private String evaluate(
      String expression, MethodBasedEvaluationContext context, Method method) {
    if (expression == null || expression.isBlank()) {
      return null;
    }
    try {
      Object value =
          expressionCache.computeIfAbsent(expression, parser::parseExpression).getValue(context);
      return (value != null) ? value.toString() : null;
    } catch (RuntimeException e) {
      LOGGER.warn("Audit SpEL '{}' on {} failed: {}", expression, method.getName(), e.getMessage());
      return null;
    }
  }

  private static String errorSummary(Throwable error) {
    if (error == null) {
      return null;
    }
    String message = (error.getMessage() != null) ? ": " + error.getMessage() : "";
    String summary = error.getClass().getSimpleName() + message;
    return (summary.length() > ERROR_SUMMARY_MAX) ? summary.substring(0, ERROR_SUMMARY_MAX) : summary;
  }
}
