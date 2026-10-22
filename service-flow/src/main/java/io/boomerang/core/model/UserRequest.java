package io.boomerang.core.model;

import io.boomerang.core.entity.UserEntity;
import java.util.HashMap;
import java.util.Map;

import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;

/*
 * The external model for a User
 */
@Data
public class UserRequest {

  @Id private String id;
  private String email;
  private String name;
  private String displayName;
  private UserType type;
  private UserStatus status;
  private Map<String, String> labels = new HashMap<>();

  public UserRequest() {}

  public UserRequest(UserEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
