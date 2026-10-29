package io.boomerang.workflow;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;

/**
 * Read access to the templates and starters every workspace may use: the system workspace's
 * workflows of type {@code template} and {@code starter}. A template is run by reference, through
 * a workflow created with a {@code templateRef}; a starter is copied, by posting its body with
 * {@code type} set to {@code workflow} to the workspace Workflow create route. Authoring is the
 * system workspace's ordinary workflow routes.
 */
@Service
public class WorkflowTemplateService {

  private final WorkflowService workflowService;

  public WorkflowTemplateService(WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  /** A template or starter by name, its tasks in their slug forms; latest unless a version is given. */
  public Workflow get(String name, Optional<Integer> version) {
    return workflowService.getShared(name, version);
  }

  /**
   * Submit a template into a workspace: the run is the workspace's link to it, adopted on first
   * use. A starter, or no template of the name, is {@code WORKFLOW_TEMPLATE_INVALID_REF}.
   */
  public WorkflowRun submit(
      String workspace, String name, WorkflowSubmitRequest request, boolean start) {
    return workflowService.submitTemplate(workspace, name, request, start);
  }

  /** The templates and starters, narrowed by labels, names and types (both when none is given). */
  public Page<Workflow> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryNames,
      Optional<List<String>> queryTypes) {
    return workflowService.queryShared(
        queryLimit, queryPage, querySort, queryLabels, queryNames, queryTypes);
  }
}
