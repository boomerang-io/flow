package io.boomerang.dispatcher.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Entity for Storing connected dispatchers
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('dispatchers')}")
public class DispatcherEntity {
  @Id private String id;
  private String name;
  private String host;
  private Integer version;
  private Date creationDate = new Date();
  private Date lastConnectedDate = new Date();
  private List<TaskType> taskTypes;
  // Where the engine reads the live log of a task this dispatcher holds, and the bearer it presents
  // there; absent when the dispatcher advertised none at registration (engine/LogClient.java).
  private String logsUrl;
  private String logsToken;

  public DispatcherEntity() {
    // Default constructor for serialization/deserialization
  }

  public DispatcherEntity(String name, String host, List<TaskType> taskTypes, Integer version) {
    this.name = name;
    this.host = host;
    this.taskTypes = taskTypes;
    this.version = version;
  }
}
