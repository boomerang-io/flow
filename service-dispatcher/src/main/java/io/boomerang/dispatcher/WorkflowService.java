package io.boomerang.dispatcher;

import io.boomerang.dispatcher.sdk.WorkflowHandler;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import io.boomerang.error.BoomerangException;
import io.boomerang.kube.KubeService;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The workflow-queue handler: provisions the workspace volume a claimed workflow run declares. The
 * SDK starts the run with the engine once this returns, and reports nothing when it throws.
 */
@Service
public class WorkflowService implements WorkflowHandler {

  private static final Logger LOGGER = LogManager.getLogger(WorkflowService.class);

  private final KubeService kubeService;

  private final WorkspaceService workspaceService;

  public WorkflowService(KubeService kubeService, WorkspaceService workspaceService) {
    this.kubeService = kubeService;
    this.workspaceService = workspaceService;
  }

  /**
   * Ensure the run's workspace claim exists when the run declares one. A claim that exists already
   * is kept, so a run handed out again provisions nothing twice.
   */
  @Override
  public void provision(WorkflowRun workflow) {
    LOGGER.info(workflow.toString());
    if (workflow.getWorkspace() == null) {
      return;
    }
    try {
      if (kubeService.checkWorkspacePVCExists(workflow.getId(), false)) {
        LOGGER.debug("Workspace for WorkflowRun ({}) already exists.", workflow.getId());
      } else {
        workspaceService.create(
            workflow.getWorkflowRef(),
            workflow.getId(),
            workflow.getWorkspace(),
            workflow.getLabels());
      }
    } catch (KubeRuntimeException | KubernetesClientException e) {
      LOGGER.error(e.getMessage());
      throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
