package io.boomerang.core.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * For now the Audit object is only that an action occurred.
 *
 * FUTURE: could include a previous and next elements of the objects themselves
 */
@Data
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('audit')}")
public class AuditEntity {
  @Id private String id;
  @Indexed private AuditScope scope;

  @Indexed
  private String selfRef; // Reference to its own object in the DB (won't exist once deleted)

  @Indexed private String selfName;
  @Indexed private String parent; // Reference to the parent audit object
  private Date creationDate = new Date();
  private List<AuditEvent> events = new LinkedList<>();
  private Map<String, String> data = new HashMap<>();

  public AuditEntity() {
    // TODO Auto-generated constructor stub
  }

  public AuditEntity(
      AuditScope scope,
      String selfRef,
      Optional<String> selfName,
      Optional<String> parent,
      AuditEvent event,
      Optional<Map<String, String>> data) {
    this.scope = scope;
    this.selfRef = selfRef;
    if (selfName.isPresent()) {
      this.selfName = selfName.get();
    }
    if (parent.isPresent()) {
      //      this.parent = new ObjectId(parent.get());
      this.parent = parent.get();
    }
    this.events.add(event);
    if (data.isPresent()) {
      this.data = data.get();
    }
  }

  @Override
  public String toString() {
    return "AuditEntity [id="
        + id
        + ", scope="
        + scope
        + ", selfRef="
        + selfRef
        + ", selfName="
        + selfName
        + ", parent="
        + parent
        + ", creationDate="
        + creationDate
        + ", events="
        + events
        + "]";
  }
}
