package io.boomerang.core;

import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@Tag(name = "Token Management", description = "Create and retrieve Tokens")
public class TokenControllerV2 {

  @Autowired private TokenService tokenService;

  @PostMapping("/token")
  @AuthCriteria(
      assignableScopes = {
        AuthScope.global,
        AuthScope.user,
        AuthScope.team,
        AuthScope.workflow,
        AuthScope.session
      },
      resource = PermissionResource.TOKEN,
      action = PermissionAction.WRITE)
  @Operation(summary = "Create Token")
  public TokenCreateResponse createToken(@RequestBody TokenCreateRequest request) {
    return tokenService.create(request);
  }

  @GetMapping("/token/query")
  @AuthCriteria(
      assignableScopes = {
        AuthScope.global,
        AuthScope.user,
        AuthScope.team,
        AuthScope.workflow,
        AuthScope.session
      },
      resource = PermissionResource.TOKEN,
      action = PermissionAction.READ)
  @Operation(summary = "Search for Tokens")
  public Page<Token> query(
      @Parameter(
              name = "types",
              description = "List of types to filter for. Defaults to all.",
              required = false)
          @RequestParam(required = false)
          Optional<List<AuthScope>> types,
      @Parameter(
              name = "principals",
              description =
                  "List of principals to filter for. Based on the types you are querying for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> principals,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "order",
              description = "Ascending (ASC) or Descending (DESC) sort order on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> order,
      @Parameter(
              name = "sort",
              description = "The element to sort onr",
              example = "0",
              required = false)
          @RequestParam(defaultValue = "creationDate")
          Optional<String> sort,
      @Parameter(
              name = "fromDate",
              description = "The unix timestamp / date to search from in milliseconds since epoch",
              example = "1677589200000",
              required = false)
          @RequestParam
          Optional<Long> fromDate,
      @Parameter(
              name = "toDate",
              description = "The unix timestamp / date to search to in milliseconds since epoch",
              example = "1680267600000",
              required = false)
          @RequestParam
          Optional<Long> toDate) {
    Optional<Date> from = Optional.empty();
    Optional<Date> to = Optional.empty();
    if (fromDate.isPresent()) {
      from = Optional.of(new Date(fromDate.get()));
    }
    if (toDate.isPresent()) {
      to = Optional.of(new Date(toDate.get()));
    }
    return tokenService.query(from, to, limit, page, order, sort, types, principals);
  }

  @DeleteMapping("/token/{id}")
  @AuthCriteria(
      assignableScopes = {
        AuthScope.global,
        AuthScope.user,
        AuthScope.team,
        AuthScope.workflow,
        AuthScope.session
      },
      resource = PermissionResource.TOKEN,
      action = PermissionAction.DELETE)
  @Operation(summary = "Delete Token")
  public ResponseEntity<?> deleteToken(
      @Parameter(name = "id", description = "ID of the Token", required = true) @PathVariable
          String id) {
    boolean result = tokenService.delete(id);
    if (result) {
      return ResponseEntity.ok().build();
    } else {
      return ResponseEntity.badRequest().build();
    }
  }
}
