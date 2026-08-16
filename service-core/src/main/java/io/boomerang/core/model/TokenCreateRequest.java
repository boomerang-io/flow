package io.boomerang.core.model;

import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class TokenCreateRequest {

  private AuthScope type;
  private String name;
  private String principal;
  private String description;
  private Date expirationDate;
  private List<String> permissions;
  private String role;
  // T6-1: caller-declared machine-actor kind (e.g. SERVICE for a dispatcher token). Null for a
  // normal human-driven token. NOTE: unlike createdBy, this is legitimately caller-set - it
  // declares what is being minted, the same way `type`/`permissions` do.
  private TokenActorKind actorKind;
}
