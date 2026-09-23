package io.boomerang.dispatcher;

import io.boomerang.dispatcher.model.Response;
import io.boomerang.dispatcher.model.WorkspaceRequest;
import io.boomerang.common.enums.StorageType;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.error.BoomerangException;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

  private static final Logger LOGGER = LogManager.getLogger(WorkflowService.class);

  private final WorkspaceStore workspaceStore;

  private final WorkspaceService workspaceService;

  public WorkflowService(WorkspaceStore workspaceStore, WorkspaceService workspaceService) {
    this.workspaceStore = workspaceStore;
    this.workspaceService = workspaceService;
  }

  /*
   * Creates the resources need for a Workflow. At this point in time the resources consist of
   * Workspace PVC's of type workflow or workflowRun. It will check if they are created prior.
   *
   * It will move the workflow from Status: Ready, Phase: Pending to Status: Running, Phase: Running
   * and return the information to the Engine.
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
                  boolean storageExists = workspaceStore.exists(workspaceRef, ws.getType());
                  if (!storageExists && ws.getSpec() != null) {
                    WorkspaceRequest request = new WorkspaceRequest();
                    request.setName(ws.getName());
                    request.setLabels(workflow.getLabels());
                    request.setType(ws.getType());
                    request.setOptional(ws.isOptional());
                    request.setSpec(ws.getSpec());
                    request.setWorkflowRef(workflow.getWorkflowRef());
                    request.setWorkflowRunRef(workflow.getId());
                    workspaceService.create(request);
                  } else if (storageExists) {
                    LOGGER.debug("Workspace (" + ws.getName() + ") storage already existed.");
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
