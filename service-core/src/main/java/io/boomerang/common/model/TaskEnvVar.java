package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

/*
 * Partially replicates Tekton EnvVar but ensures that the SDK Model is not exposed
 * as the controllers model
 *
 * Reference:
 * - import io.fabric8.kubernetes.api.model.EnvVar;
 */
@Data
@JsonIgnoreProperties
public class TaskEnvVar {

  @JsonProperty("name")
  private String name;

  // Left out of toString: a value can be a filled-in secret that carries no type.
  @JsonProperty("value")
  @ToString.Exclude
  private String value;

  /** No args constructor for use in serialization */
  public TaskEnvVar() {}

  /**
   * @param name
   * @param description
   */
  public TaskEnvVar(String name, String value) {
    super();
    this.name = name;
    this.value = value;
  }
}
