package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.common.enums.RetryScope;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * The body of a retry. An absent body, or {@code scope=all}, is a fresh run of the whole graph.
 * {@code nodes} is read only when the scope is {@code nodes}. {@code params} and {@code labels}
 * are merged over the origin run's own.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowRunRetryRequest {

  private RetryScope scope = RetryScope.all;
  private List<String> nodes = new LinkedList<>();
  private List<RunParam> params = new LinkedList<>();
  private Map<String, String> labels = new HashMap<>();
}
