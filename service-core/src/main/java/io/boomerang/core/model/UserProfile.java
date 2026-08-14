package io.boomerang.core.model;

import io.boomerang.core.entity.UserEntity;
import io.boomerang.workflow.model.TeamSummary;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/*
 * Utilised by the Profile endpoint
 *
 * Same as User but with Teams & permissions
 */
@Data
public class UserProfile extends UserEntity {

  List<TeamSummary> teams;

  List<String> permissions;

  public UserProfile() {}

  public UserProfile(UserEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
