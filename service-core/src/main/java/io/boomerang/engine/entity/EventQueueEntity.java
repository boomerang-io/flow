package io.boomerang.engine.entity;

import java.util.Date;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.http.HttpEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


/*
 * Entity for Manual Action and Approval Action
 * 
 * Shared with the Workflow Service
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('event_queue')}")
public class EventQueueEntity {

  @Id
  private String id;
  private String url;
  private HttpEntity<byte[]> request;
  private Date creationDate = new Date();
  
  public EventQueueEntity() {
  }
  
  public EventQueueEntity(String url, HttpEntity<byte[]> request) {
    this.url = url;
    this.request = request;
  }
}
