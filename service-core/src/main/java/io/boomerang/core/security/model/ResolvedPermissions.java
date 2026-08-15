package io.boomerang.core.security.model;

import io.boomerang.core.security.enums.AuthScope;
import java.util.List;
import lombok.Data;

@Data
public class ResolvedPermissions {

  private AuthScope scope;
  private String principal;
  private List<String> actions;

  public ResolvedPermissions() {}

  public ResolvedPermissions(AuthScope scope, String principal, List<String> actions) {
    this.scope = scope;
    this.principal = principal;
    this.actions = actions;
  }
}
