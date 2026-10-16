package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.model.Actioner;
import java.util.Date;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Entity for Manual Action and Approval Action
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('actions')}")
public class ActionEntity {

  @Id private String id;
  private String workflowRef;
  private String workflowRunRef;
  private String taskRunRef;
  private List<Actioner> actioners;
  private ActionStatus status;
  private ActionType type;
  private String instructions;
  private Date creationDate;
  private int numberOfApprovers;
  private String approverGroupRef;
}
