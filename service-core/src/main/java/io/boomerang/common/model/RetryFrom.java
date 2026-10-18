package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RetryScope;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;

/**
 * Where a retried run started from. Present only on a run created by a scoped retry: the scope,
 * and the task names when the scope is {@code nodes}. The tasks before that point were carried
 * over from the origin run rather than executed again.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryFrom {

  private RetryScope scope;
  private List<String> nodes = new LinkedList<>();
}
