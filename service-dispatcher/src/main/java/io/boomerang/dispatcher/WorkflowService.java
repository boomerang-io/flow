package io.boomerang.dispatcher;

import io.boomerang.dispatcher.model.Response;
import io.boomerang.dispatcher.model.WorkspaceRequest;
import io.boomerang.dispatcher.sdk.WorkflowHandler;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import io.boomerang.kube.StorageType;
import io.boomerang.error.BoomerangException;
import io.boomerang.kube.KubeService;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The workflow-queue handler: provisions the workspace volumes a claimed workflow run declares. The
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

  @Override
  public void provision(WorkflowRun workflow) {
    execute(workflow);
  }

  /*
   * Creates the resources need for a Workflow. At this point in time the resources consist of
   * Workspace PVC's of type workflow or workflowRun. It will check if they are created prior;
   * one that exists already is kept, so a run handed out again provisions nothing twice.
   */
  public Response execute(WorkflowRun workflow) {
    Response response =
        new Response("0", "WorkflowRun (" + workflow.getId() + ") has been created successfully.");
    LOGGER.info(workflow.toString());
    if (workflow.getWorkspaces() != null && !workflow.getWorkspaces().isEmpty()) {
      workflow.getWorkspaces().stream()
          .filter(ws -> StorageType.fromLabel(ws.getType()).isPresent())
          .forEach(
              ws -> {
                try {
                  // Based on the Workspace Type we set the workspaceRef to be the WorkflowRef or
                  // the
                  // WorkflowRunRef
                  String workspaceRef =
                      workspaceService.getWorkspaceRef(
                          ws.getType(), workflow.getWorkflowRef(), workflow.getId());
                  boolean pvcExists =
                      kubeService.checkWorkspacePVCExists(workspaceRef, ws.getType(), false);
                  if (!pvcExists && ws.getSpec() != null) {
                    WorkspaceRequest request = new WorkspaceRequest();
                    request.setName(ws.getName());
                    request.setLabels(workflow.getLabels());
                    request.setType(ws.getType());
                    request.setOptional(ws.isOptional());
                    request.setSpec(ws.getSpec());
                    request.setWorkflowRef(workflow.getWorkflowRef());
                    request.setWorkflowRunRef(workflow.getId());
                    workspaceService.create(request);
                  } else if (pvcExists) {
                    LOGGER.debug("Workspace (" + ws.getName() + ") PVC already existed.");
                  }
                } catch (KubeRuntimeException | KubernetesClientException e) {
                  LOGGER.error(e.getMessage());
                  throw new BoomerangException(
                      e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
              });
    } else {
      response =
          new Response("0", "WorkflowRun (" + workflow.getId() + ") created without workspaces.");
    }
    return response;
  }
}
