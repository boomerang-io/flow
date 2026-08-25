package io.boomerang.api.model;

import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.workspace.model.WorkspaceSummary;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/*
 * Utilised by the Profile endpoint
 *
 * Same as User but with Teams & permissions
 *
 * Stays in api.model deliberately. ProfileControllerV2 is its only user, and the type spans core
 * (UserEntity) and workspace (WorkspaceSummary) - core does not depend on workspace, so moving this
 * into core.model would create that dependency. It is an api-layer composition, which is exactly
 * the reason ProfileControllerV2 composes the response there rather than in core.UserService.
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
