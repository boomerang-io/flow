package io.boomerang.core.audit;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The instance-wide read surface over the audit trail. Read-only by design: an audit row is never
 * changed or removed through the API - retention is the TTL {@link AuditRetentionService} applies.
 *
 * <p>Both routes are platform-admin only. {@code @AuthCriteria} admits the token classes a signed
 * -in admin and an automation actually carry, and {@link #requireGlobalGrant()} then insists the
 * matching grant be {@code global}: layer 1 matches a permission string without looking at the
 * scope it was granted at, so a workspace owner's {@code **}/{@code **} would otherwise satisfy
 * {@code system/read} and read every workspace's trail. Same shape as {@code
 * UserService.requireSelfOrGlobalGrant}. A workspace-scoped view is a separate, future route.
 */
@RestController
@RequestMapping("/api/v2/audit")
@Tag(
    name = "Audit",
    description = "Query the security audit trail: who did what, when, and with what outcome.")
public class AuditControllerV2 {

  /**
   * Applied when the caller sends no {@code from}. Every listing is time-bounded so the page and
   * its count ride the {@code time} index instead of scanning the retention window.
   */
  static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 100;

  private final AuditQueryService auditQueryService;
  private final AuditEventEmitter auditEventEmitter;
  private final AuditRetentionService auditRetentionService;
  private final IdentityService identityService;

  public AuditControllerV2(
      AuditQueryService auditQueryService,
      AuditEventEmitter auditEventEmitter,
      AuditRetentionService auditRetentionService,
      IdentityService identityService) {
    this.auditQueryService = auditQueryService;
    this.auditEventEmitter = auditEventEmitter;
    this.auditRetentionService = auditRetentionService;
    this.identityService = identityService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(
      summary = "Query the audit trail",
      description =
          "One event per audited attempt, newest first, including failed and denied ones. Without "
              + "a from date the last 30 days are returned.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  public Page<AuditEvent> query(
      @Parameter(name = "actor", description = "Actor id or name, any part of either, ignoring case")
          @RequestParam(required = false)
          Optional<String> actor,
      @Parameter(name = "action", description = "AuditAction names to filter for", example = "CREATE,DELETE")
          @RequestParam(required = false)
          Optional<List<AuditAction>> action,
      @Parameter(name = "outcome", description = "AuditOutcome names to filter for", example = "DENIED")
          @RequestParam(required = false)
          Optional<List<AuditOutcome>> outcome,
      @Parameter(
              name = "level",
              description = "The level the event was captured at",
              example = "DESTRUCTIVE")
          @RequestParam(required = false)
          Optional<List<AuditLevel>> level,
      @Parameter(name = "resourceType", description = "Resource types to filter for", example = "workflow")
          @RequestParam(required = false)
          Optional<List<String>> resourceType,
      @Parameter(name = "resourceId", description = "A single resource id")
          @RequestParam(required = false)
          Optional<String> resourceId,
      @Parameter(name = "workspaceId", description = "Owning workspace ids to filter for")
          @RequestParam(required = false)
          Optional<List<String>> workspaceId,
      @Parameter(name = "from", description = "ISO-8601 instant; defaults to 30 days ago", example = "2026-09-01T00:00:00Z")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Optional<Instant> from,
      @Parameter(name = "to", description = "ISO-8601 instant, exclusive", example = "2026-10-01T00:00:00Z")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Optional<Instant> to,
      @Parameter(name = "page", description = "Page Number", example = "0")
          @RequestParam(required = false, defaultValue = "0")
          int page,
      @Parameter(name = "limit", description = "Result Size, capped at 100", example = "25")
          @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit,
      @Parameter(name = "order", description = "Ascending or Descending (default) by time")
          @RequestParam(required = false, defaultValue = "DESC")
          Direction order) {
    requireGlobalGrant();
    Pageable pageable = PageRequest.of(page, Math.min(limit, MAX_LIMIT), Sort.by(order, "time"));
    return auditQueryService
        .query(
            pageable,
            workspaceId,
            actor,
            names(action),
            names(level),
            resourceType,
            resourceId,
            names(outcome),
            Optional.of(windowStart(from)),
            to.map(Date::from))
        .map(AuditEvent::from);
  }

  @GetMapping(value = "/stats")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(
      summary = "Count the audit trail by outcome",
      description =
          "Counts the same filters and window as the listing, alongside the configured capture "
              + "state, level and retention.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  public AuditStats stats(
      @RequestParam(required = false) Optional<String> actor,
      @RequestParam(required = false) Optional<List<AuditAction>> action,
      @RequestParam(required = false) Optional<List<AuditOutcome>> outcome,
      @RequestParam(required = false) Optional<List<AuditLevel>> level,
      @RequestParam(required = false) Optional<List<String>> resourceType,
      @RequestParam(required = false) Optional<String> resourceId,
      @RequestParam(required = false) Optional<List<String>> workspaceId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Optional<Instant> from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Optional<Instant> to) {
    requireGlobalGrant();
    Date start = windowStart(from);
    Date end = to.map(Date::from).orElse(null);
    Map<AuditOutcome, Long> counts =
        auditQueryService.outcomeCounts(
            workspaceId,
            actor,
            names(action),
            names(level),
            resourceType,
            resourceId,
            names(outcome),
            Optional.of(start),
            Optional.ofNullable(end));
    return new AuditStats(
        start,
        end,
        counts.values().stream().mapToLong(Long::longValue).sum(),
        counts,
        auditEventEmitter.captureEnabled(),
        auditEventEmitter.configuredLevel(),
        auditRetentionService.configuredRetentionDays());
  }

  /** The caller's own {@code from}, or the default window back from now. */
  private static Date windowStart(Optional<Instant> from) {
    return Date.from(from.orElseGet(() -> Instant.now().minus(DEFAULT_WINDOW)));
  }

  private static <E extends Enum<E>> Optional<List<String>> names(Optional<List<E>> values) {
    return values
        .filter(list -> !list.isEmpty())
        .map(list -> list.stream().map(Enum::name).toList());
  }

  /**
   * Refuse a caller whose {@code system/read} grant is workspace-scoped: the trail spans every
   * workspace, so only a platform-wide grant may read it.
   */
  private void requireGlobalGrant() {
    Token identity = identityService.getCurrentIdentity();
    String required =
        "(\\*{2}|"
            + PermissionResource.SYSTEM.getLabel()
            + ")\\/(\\*{2}|"
            + PermissionAction.READ.getLabel()
            + ")";
    if (identity == null
        || identity.getPermissions().stream()
            .filter(permission -> PermissionScope.global.equals(permission.getScope()))
            .flatMap(permission -> permission.getActions().stream())
            .noneMatch(action -> action.matches(required))) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
  }
}
