package io.boomerang.flow.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@link Workspaces#create} sends: the workspace's name and display name, the embedding
 * product's own id for the record it mirrors as {@code externalRef}, and labels. The caller
 * becomes the owner; members, quotas and parameters are set through their own routes.
 *
 * <pre>{@code
 * WorkspaceRequest.of("archie-6ae2e9cc99b43262aa3e8e28", "Acme").externalRef("6ae2e9cc99b43262aa3e8e28")
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WorkspaceRequest {

  private final String name;
  private final String displayName;
  private String externalRef;
  private final Map<String, String> labels = new LinkedHashMap<>();

  private WorkspaceRequest(String name, String displayName) {
    this.name = name;
    this.displayName = displayName;
  }

  /** A workspace named {@code name}, shown as {@code displayName}. */
  public static WorkspaceRequest of(String name, String displayName) {
    return new WorkspaceRequest(name, displayName);
  }

  /** The embedding product's own id for the record this workspace mirrors; carried once. */
  public WorkspaceRequest externalRef(String externalRef) {
    this.externalRef = externalRef;
    return this;
  }

  public WorkspaceRequest label(String key, String value) {
    labels.put(key, value);
    return this;
  }

  public String getName() {
    return name;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getExternalRef() {
    return externalRef;
  }

  public Map<String, String> getLabels() {
    return labels;
  }
}
