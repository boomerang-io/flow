package io.boomerang.dispatcher;

import io.boomerang.dispatcher.model.Response;
import io.boomerang.dispatcher.sdk.model.WorkflowWorkspace;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.kube.KubeServiceImpl;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Provisions and releases the one workspace claim a run owns. A field the run leaves blank takes
 * the {@code kube.workspace.storage.*} default.
 */
@Service
public class WorkspaceService {

  private static final Logger LOGGER = LogManager.getLogger(WorkspaceService.class);

  @Value("${kube.workspace.storage.size}")
  protected String storageSize;

  @Value("${kube.workspace.storage.class}")
  protected String storageClassName;

  @Value("${kube.workspace.storage.accessMode}")
  protected String storageAccessMode;

  @Value("${kube.timeout.waitUntil}")
  protected long waitUntilTimeout;

  private final KubeServiceImpl kubeService;

  public WorkspaceService(KubeServiceImpl kubeService) {
    this.kubeService = kubeService;
  }

  /** Create the run's workspace claim, labelled with the run's labels. */
  public Response create(
      String workflowRef,
      String workflowRunRef,
      WorkflowWorkspace workspace,
      Map<String, String> labels) {
    Response response =
        new Response(
            "0", "Workspace for WorkflowRun (" + workflowRunRef + ") has been created successfully.");
    LOGGER.info("CreateWorkspace Request: workflowRunRef={}, {}", workflowRunRef, workspace);
    try {
      kubeService.createWorkspacePVC(
          workflowRef,
          workflowRunRef,
          labels,
          orDefault(workspace.getSize(), storageSize),
          orDefault(workspace.getClassName(), storageClassName),
          orDefault(workspace.getAccessMode(), storageAccessMode),
          waitUntilTimeout);
    } catch (KubeRuntimeException | KubernetesClientException | InterruptedException e) {
      LOGGER.error(e.getMessage());
      throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
    } catch (IllegalArgumentException e) {
      if (e.getMessage().contains("condition not found")) {
        throw new BoomerangException(
            e, BoomerangError.PVC_CREATE_CONDITION_NOT_MET, "" + waitUntilTimeout);
      }
      throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
    } catch (BoomerangException e) {
      throw e;
    } catch (RuntimeException e) {
      // Anything unexpected surfaces as the same provisioning failure, never a raw exception.
      LOGGER.error("Unexpected error creating Workspace for WorkflowRun ({}).", workflowRunRef, e);
      throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return response;
  }

  /** Release the run's workspace claim; a claim that is already gone is already released. */
  public Response delete(String workflowRunRef) {
    Response response = new Response("0", "Workspace has been successfully deleted.");
    try {
      kubeService.deleteWorkspacePVC(workflowRunRef);
    } catch (KubeRuntimeException e) {
      throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
    LOGGER.info("deleteWorkspace() - " + response.getMessage());
    return response;
  }

  private static String orDefault(String value, String fallback) {
    return (value != null && !value.isBlank()) ? value : fallback;
  }
}
