package io.boomerang.event;

import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.OutboxStatus;
import io.boomerang.event.model.OutboxReplayResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * The operator surface for the events outbox. It sits in the event package rather than on
 * SystemControllerV2 because core must not import a feature package; the routes are still under
 * /api/v2/system and carry the system permission.
 */
@RestController
@RequestMapping("/api/v2/system/outbox")
@Tag(name = "System", description = "Inspect and replay the outbound event outbox.")
public class OutboxControllerV2 {

  private final OutboxService outboxService;

  public OutboxControllerV2(OutboxService outboxService) {
    this.outboxService = outboxService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.global})
  @Operation(
      summary = "List outbox rows, dead ones by default.",
      description =
          "A row that failed delivery carries lastError, the most recent failure; a dead row also "
              + "carries deadAt, when it gave up. Rows that died before these were recorded carry "
              + "neither.")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK")})
  public Page<EventOutboxEntity> query(
      @Parameter(name = "status", description = "Delivery status to list", example = "dead")
          @RequestParam(required = false, defaultValue = "dead")
          OutboxStatus status,
      @Parameter(name = "page", description = "Page Number", example = "0")
          @RequestParam(required = false, defaultValue = "0")
          int page,
      @Parameter(name = "limit", description = "Result Size", example = "100")
          @RequestParam(required = false, defaultValue = "100")
          int limit) {
    return outboxService.query(status, page, limit);
  }

  @PutMapping(value = "/replay")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.global})
  @Operation(
      summary = "Replay outbox rows",
      description =
          "Puts rows back in the queue for the next dispatcher pass. Replays the named ids, or "
              + "every row in the given status when no ids are supplied.")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK")})
  public OutboxReplayResponse replay(
      @Parameter(name = "ids", description = "Outbox row ids to replay")
          @RequestParam(required = false)
          Optional<List<String>> ids,
      @Parameter(name = "status", description = "Replay every row in this status", example = "dead")
          @RequestParam(required = false, defaultValue = "dead")
          OutboxStatus status,
      @Parameter(
              name = "olderThan",
              description = "Only replay rows that occurred before this epoch millisecond")
          @RequestParam(required = false)
          Optional<Long> olderThan) {
    return new OutboxReplayResponse(
        outboxService.replay(
            ids.orElse(List.of()), status, olderThan.map(Date::new).orElse(null)));
  }
}
