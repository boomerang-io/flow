package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a retried run started from: present only on a run a scoped retry created. The tasks
 * before that point were carried over from the origin run, so a dispatcher never sees them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryFrom {

  private RetryScope scope;
  private List<String> nodes = new ArrayList<>();

  public RetryScope getScope() {
    return scope;
  }

  public void setScope(RetryScope scope) {
    this.scope = scope;
  }

  public List<String> getNodes() {
    return nodes;
  }

  public void setNodes(List<String> nodes) {
    this.nodes = nodes;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof RetryFrom retryFrom)
        && scope == retryFrom.scope
        && Objects.equals(nodes, retryFrom.nodes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scope, nodes);
  }

  @Override
  public String toString() {
    return "RetryFrom[scope=" + scope + ", nodes=" + nodes + "]";
  }
}
