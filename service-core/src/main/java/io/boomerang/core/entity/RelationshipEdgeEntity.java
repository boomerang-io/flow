package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.core.enums.RelationshipLabel;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Entity for Relationships
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('rel_edges')}")
public class RelationshipEdgeEntity {

  @Id private String id;
  private Date creationDate = new Date();
  private String from;
  private String label;
  private String to;
  private Map<String, String> data = new HashMap<>();

  public RelationshipEdgeEntity() {}

  public RelationshipEdgeEntity(
      String from, String label, String to, Optional<Map<String, String>> data) {
    this.from = from;
    this.label = label;
    this.to = to;
    if (data.isPresent()) {
      this.setData(data.get());
    }
  }

  public RelationshipEdgeEntity(
      String from, RelationshipLabel label, String to, Optional<Map<String, String>> data) {
    this.from = from;
    this.label = label.getLabel();
    this.to = to;
    if (data.isPresent()) {
      this.setData(data.get());
    }
  }

  @Override
  public String toString() {
    return "RelationshipEdgeEntity [id="
        + id
        + ", creationDate="
        + creationDate
        + ", from="
        + from
        + ", label="
        + label
        + ", to="
        + to
        + ", data="
        + data
        + "]";
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Date getCreationDate() {
    return creationDate;
  }

  public String getFrom() {
    return from.toString();
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public String getTo() {
    return to.toString();
  }

  public void setTo(String to) {
    this.to = to;
  }

  public Map<String, String> getData() {
    return data;
  }

  public void setData(Map<String, String> data) {
    this.data = data;
  }
}
