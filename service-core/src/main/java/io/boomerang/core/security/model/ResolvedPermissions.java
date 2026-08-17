package io.boomerang.core.security.model;

import io.boomerang.core.security.enums.PermissionScope;
import java.util.List;
import lombok.Data;

@Data
public class ResolvedPermissions {

  private PermissionScope scope;
  private String principal;
  private List<String> actions;

  public ResolvedPermissions() {}

  public ResolvedPermissions(PermissionScope scope, String principal, List<String> actions) {
    this.scope = scope;
    this.principal = principal;
    this.actions = actions;
  }
}
