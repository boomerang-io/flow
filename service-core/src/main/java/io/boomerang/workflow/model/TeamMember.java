package io.boomerang.workflow.model;

import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.core.entity.UserEntity;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class TeamMember {
   
  @Id
  private String id;
  private String email;
  private String name;
  private String role;
   
  public TeamMember() {
  }
  
  public TeamMember(UserEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
  
  public TeamMember(UserEntity entity, String role) {
    BeanUtils.copyProperties(entity, this);
    this.role = role;
  }
  
  public String getId() {
    return id;
  }
  public void setId(String id) {
    this.id = id;
  }
  public String getEmail() {
    return email;
  }
  public void setEmail(String email) {
    this.email = email;
  }
  public String getName() {
    return name;
  }
  public void setName(String name) {
    this.name = name;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
