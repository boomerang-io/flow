package io.boomerang.security.model;

import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import lombok.Data;

@Data
public class Permission {
  private PermissionResource scope;
  private PermissionAction action;

  public Permission() {}

  public Permission(String permission) {
    String[] spread = permission.split("/");
    PermissionResource.valueOf(spread[0]);
  }

  public Permission(PermissionResource scope, PermissionAction action) {
    this.scope = scope;
    this.action = action;
  }

  public String toString() {
    return scope + "\\" + action;
  }
}
