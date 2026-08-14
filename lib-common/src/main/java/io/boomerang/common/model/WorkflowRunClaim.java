package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;

/*
 * Wire model handed to the worker when it claims a WorkflowRun for provision or teardown.
 *
 * Carries the fields the worker acts on - crucially phase, which the public WorkflowRun no longer
 * exposes. Populated from the claimed WorkflowRunEntity pre-image (which reflects the post-claim
 * phase). Not a public API response type.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRunClaim {

  @Id private String id;
  private Map<String, String> labels = new HashMap<>();
  private RunStatus status;
  private RunPhase phase;
  private String workflowRef;
  private List<RunParam> params = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();
}
