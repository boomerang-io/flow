package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one shared filesystem a run's tasks can read and write. {@code size} is a Kubernetes
 * quantity ({@code 2Gi}); a blank mount path is the server's default, a blank class or access
 * mode the cluster's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowWorkspace(
    String size, String mountPath, String className, String accessMode) {

  public static WorkflowWorkspace of(String size) {
    return new WorkflowWorkspace(size, null, null, null);
  }
}
