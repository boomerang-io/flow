package io.boomerang.workspace.model;

import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/*
 * Utilised by the Profile endpoint
 *
 * Same as User but with Teams & permissions
 *
 * Lives in workspace.model, NOT core.model. The type spans core (UserEntity, ResolvedPermissions)
 * and workspace (WorkspaceSummary). io.boomerang.core has zero outbound feature-package imports and
 * must keep it, so core.model cannot reference WorkspaceSummary; workspace already depends on core,
 * so this direction is free. It sits beside its only user, ProfileControllerV2.
 */
@Data
public class UserProfile extends UserEntity {

  List<WorkspaceSummary> teams;

  List<ResolvedPermissions> permissions;

  public UserProfile() {}

  public UserProfile(UserEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
