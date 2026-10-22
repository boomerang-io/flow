package io.boomerang.core.model;

import io.boomerang.core.entity.RoleEntity;
import io.boomerang.security.enums.AuthScope;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;

@Data
public class Role {

  //  @JsonIgnore
  private String id;
  private AuthScope type;
  private String name;
  private String description;
  private List<String> permissions = new LinkedList<>();

  public Role() {}

  public Role(RoleEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
