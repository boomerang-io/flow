package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('tokens')}")
public class TokenEntity {

  @Id private String id;
  // T6-3: the token's CLASS (actor/ceiling-typed: session/user/key/global) - never a resource
  // scope. Each grant's own scope lives on ResolvedPermissions.scope (PermissionScope) instead.
  private AuthScope type;
  private String name;
  private String description;
  private Date creationDate = new Date();
  private Date expirationDate;
  private String principal;
  private List<ResolvedPermissions> permissions = new LinkedList<>();
  private String token;

  // T6-1: orthogonal machine-actor discriminator (null on every pre-existing/human token).
  private TokenActorKind actorKind;
  // Server-injected from the authenticated principal at creation time - NEVER read from the
  // request body. Null on tokens created before this field existed / by unauthenticated flows
  // (e.g. bootstrap). Absent-tolerant: no loader backfill needed.
  private String createdBy;
  // Best-effort, throttled (~5 min) "last used" stamp - see TokenService#touchLastUsed.
  private Date lastUsedAt;
}
