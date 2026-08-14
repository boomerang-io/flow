package io.boomerang.security.model;

import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import lombok.Data;

@Data
public class Permission {
  private PermissionResource resource;
  private PermissionAction action;

  public Permission() {}

  public Permission(String permission) {
    String[] spread = permission.split("/");
    this.resource = PermissionResource.valueOfLabel(spread[0]);
    this.action = PermissionAction.valueOfLabel(spread[1]);
  }

  public Permission(PermissionResource resource, PermissionAction action) {
    this.resource = resource;
    this.action = action;
  }

  public String toString() {
    return resource + "\\" + action;
  }
}
