package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Entity for Relationships
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('rel_nodes')}")
@CompoundIndexes({
  @CompoundIndex(name = "type_slug_idx", def = "{'type' : -1, 'slug': -1}"),
  @CompoundIndex(name = "type_ref_idx", def = "{'type' : -1, 'ref': -1}")
})
public class RelationshipNodeEntity {

  @Id private String id;
  private Date creationDate = new Date();
  private String type;
  private String ref;
  private String slug;
  private Map<String, String> data = new HashMap<>();

  public RelationshipNodeEntity() {
    // TODO Auto-generated constructor stub
  }

  // TODO figure out if we need both ref and slug or ref can be either slug or _id
  public RelationshipNodeEntity(
      String type, String ref, String slug, Optional<Map<String, String>> data) {
    this.id = type + ":" + ref;
    this.type = type;
    this.ref = ref;
    this.slug = slug;
    if (data.isPresent()) {
      this.data = data.get();
    }
  }

  @Override
  public String toString() {
    return "RelationshipNodeEntity [id="
        + id
        + ", creationDate="
        + creationDate
        + ", type="
        + type
        + ", ref="
        + ref
        + ", slug="
        + slug
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

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public Map<String, String> getData() {
    return data;
  }

  public void setData(Map<String, String> data) {
    this.data = data;
  }
}
