package io.boomerang.core.model;

import io.boomerang.core.entity.UserEntity;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/*
 * The external model for a User
 */
@Data
public class User extends UserEntity {

  public User() {}

  public User(UserEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

  public User(ExternalUserProfile entity) {
    BeanUtils.copyProperties(entity, this, "personalizations", "lowerLevelGroups");
    this.getSettings().setHasConsented(entity.getHasConsented());
    this.getSettings().setIsFirstVisit(entity.getIsFirstVisit());
  }

  @Override
  public String toString() {
    return "User [getStatus()="
        + getStatus()
        + ", getId()="
        + getId()
        + ", getName()="
        + getName()
        + ", getEmail()="
        + getEmail()
        + ", getType()="
        + getType()
        + ", getCreationDate()="
        + getCreationDate()
        + ", getLastLoginDate()="
        + getLastLoginDate()
        + ", getLabels()="
        + getLabels()
        + ", getSettings()="
        + getSettings()
        + ", getClass()="
        + getClass()
        + ", hashCode()="
        + hashCode()
        + ", toString()="
        + super.toString()
        + "]";
  }
}
