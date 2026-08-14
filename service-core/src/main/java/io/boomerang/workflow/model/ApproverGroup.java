package io.boomerang.workflow.model;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.springframework.beans.BeanUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.workflow.entity.ApproverGroupEntity;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class ApproverGroup {

  private String id;
  private String name;
  private Date creationDate = new Date();
  private List<TeamMember> approvers = new LinkedList<>();  

  public ApproverGroup() {
    
  }
  
  public ApproverGroup(ApproverGroupEntity entity) {
    BeanUtils.copyProperties(entity, this, "approvers");
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Date getCreationDate() {
    return creationDate;
  }

  public void setCreationDate(Date creationDate) {
    this.creationDate = creationDate;
  }

  public List<TeamMember> getApprovers() {
    return approvers;
  }

  public void setApprovers(List<TeamMember> approvers) {
    this.approvers = approvers;
  }
}
