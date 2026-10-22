package io.boomerang.engine.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Entity for Storing connected agents
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('agents')}")
public class AgentEntity {
  @Id private String id;
  private String name;
  private String host;
  private Integer version;
  private Date creationDate = new Date();
  private Date lastConnectedDate = new Date();
  //  private List<String> workflowTypes;
  private List<TaskType> taskTypes;

  public AgentEntity() {
    // Default constructor for serialization/deserialization
  }

  public AgentEntity(String name, String host, List<TaskType> taskTypes, Integer version) {
    this.name = name;
    this.host = host;
    this.taskTypes = taskTypes;
    this.version = version;
  }
}
