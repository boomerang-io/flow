package io.boomerang.workflow.entity;

import java.util.Date;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('approver_groups')}")
public class ApproverGroupEntity {

  private String id;
  private String name;
  private Date creationDate = new Date();
  private List<String> approvers;

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
  
  public List<String> getApprovers() {
    return approvers;
  }
  
  public void setApprovers(List<String> approvers) {
    this.approvers = approvers;
  }
}
