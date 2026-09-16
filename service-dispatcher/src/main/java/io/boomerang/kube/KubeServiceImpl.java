package io.boomerang.kube;

import io.boomerang.dispatcher.model.HeldWorkspace;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

// The Docker executor has its own workspace store and no cluster to talk to, so this bean - and
// the Kubernetes client it would build - exists only for the two Kubernetes runtimes.
@Component
@ConditionalOnExpression("'${dispatcher.executor}' != 'docker'")
public class KubeServiceImpl implements KubeService {

  private static final Logger LOGGER = LogManager.getLogger(KubeServiceImpl.class);

  @Autowired protected KubeHelperService helperKubeService;

  protected static final Integer ONE_DAY_IN_SECONDS = 86400; // 60*60*24

  @Value("${kube.image.pullPolicy}")
  protected String kubeImagePullPolicy;

  @Value("${kube.image.pullSecret}")
  protected String kubeImagePullSecret;

  @Value("${kube.task.backOffLimit}")
  protected Integer kubeJobBackOffLimit;

  @Value("${kube.task.restartPolicy}")
  protected String kubeJobRestartPolicy;

  @Value("${kube.task.ttlDays}")
  protected Integer kubeJobTTLDays;

  @Value("${dispatcher.tasks.serviceaccount}")
  protected String kubeJobServiceAccount;

  @Value("${kube.resource.limit.ephemeral-storage}")
  private String kubeResourceLimitEphemeralStorage;

  @Value("${kube.resource.request.ephemeral-storage}")
  private String kubeResourceRequestEphemeralStorage;

  @Value("${kube.resource.limit.memory}")
  private String kubeResourceLimitMemory;

  @Value("${kube.resource.request.memory}")
  private String kubeResourceRequestMemory;

  @Value("${kube.task.storage.data.memory}")
  private Boolean kubeWorkerStorageDataMemory;

  @Value("${dispatcher.tasks.hostaliases}")
  protected String kubeHostAliases;

  protected KubernetesClient client = null;

  public KubeServiceImpl(@Lazy KubernetesClient client) {
    this.client = client;
  }

  // Tests swap in the mock-server client after the context is up.
  public void setClient(KubernetesClient client) {
    LOGGER.info("Creating Client with default namespace: " + client.getNamespace());
    this.client = client;
  }

  @Override
  public boolean exists(String workspaceRef, String workspaceType) {
    if (workspaceRef == null || workspaceType == null) {
      return false;
    }
    Map<String, String> labelSelector =
        helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null);
    try {
      PersistentVolumeClaimList pvcList =
          client.persistentVolumeClaims().withLabels(labelSelector).list();
      LOGGER.info("PVC List: " + pvcList);
      return !pvcList.getItems().isEmpty();
    } catch (KubernetesClientException e) {
      LOGGER.error("No PVC found matching selector: " + labelSelector, e);
      return false;
    }
  }

  protected String getPVCName(Map<String, String> labels) {
    PersistentVolumeClaimList pvcList = client.persistentVolumeClaims().withLabels(labels).list();

    LOGGER.debug("PVC List: " + pvcList);

    List<PersistentVolumeClaim> pvcs = pvcList.getItems();
    if (pvcs.isEmpty()) {
      throw new KubeRuntimeException(
          "No PersistentVolumeClaim found matching label selector: " + labels);
    }
    if (pvcs.size() > 1) {
      LOGGER.warn(
          "Found "
              + pvcs.size()
              + " PersistentVolumeClaims matching label selector "
              + labels
              + ", using the first: "
              + pvcs.stream()
                  .map(
                      pvc ->
                          pvc.getMetadata().getName()
                              + "("
                              + pvc.getMetadata().getCreationTimestamp()
                              + ")")
                  .collect(Collectors.joining(", ")));
    }
    String name = pvcs.get(0).getMetadata().getName();
    if (name == null) {
      throw new KubeRuntimeException(
          "PersistentVolumeClaim matching label selector " + labels + " has no name.");
    }
    LOGGER.debug(" Chosen PVC Name: " + name);
    return name;
  }

  @Override
  public void create(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws InterruptedException {
    createWorkspacePVC(
        workflowRef, workspaceRef, workspaceType, customLabels, size, className, accessMode, waitSeconds);
  }

  @Override
  public PersistentVolumeClaim createWorkspacePVC(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws KubernetesClientException, InterruptedException {
    return createPVC(
        helperKubeService.getWorkspaceAnnotations(workflowRef, workspaceRef, workspaceType),
        helperKubeService.getWorkspaceLabels(
            workflowRef, workspaceRef, workspaceType, customLabels),
        size,
        className,
        accessMode,
        waitSeconds);
  }

  private PersistentVolumeClaim createPVC(
      Map<String, String> annotations,
      Map<String, String> labels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws KubernetesClientException, InterruptedException {

    // A blank class MUST leave storageClassName unset: an empty string tells Kubernetes "no
    // dynamic provisioning, static volumes only" and the claim never binds, whereas an absent
    // field selects the cluster's default StorageClass.
    boolean useDefaultClass = className == null || className.isBlank();
    LOGGER.info(
        "Creating PersistentVolumeClaim (size={}, class={}, accessMode={})",
        size,
        useDefaultClass ? "<cluster default>" : className,
        accessMode);

    PersistentVolumeClaimBuilder builder =
        new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withGenerateName(helperKubeService.getPrefixPVC() + "-")
            .withLabels(labels)
            .withAnnotations(annotations)
            .endMetadata();
    PersistentVolumeClaim persistentVolumeClaim =
        (useDefaultClass
                ? builder.withNewSpec()
                : builder.withNewSpec().withStorageClassName(className))
            .withAccessModes(accessMode)
            .withNewResources()
            .addToRequests("storage", new Quantity(size))
            .endResources()
            .endSpec()
            .build();

    PersistentVolumeClaim result =
        client.persistentVolumeClaims().resource(persistentVolumeClaim).create();

    client
        .resource(result)
        .waitUntilCondition(
            r ->
                r.getStatus() != null
                    && ("Bound".equals(r.getStatus().getPhase())
                        || "Pending".equals(r.getStatus().getPhase())),
            waitSeconds,
            TimeUnit.SECONDS);

    LOGGER.info(result);
    return result;
  }

  /**
   * Return every workspace claim this dispatcher's namespace holds, whoever created it. The
   * cluster is the source of truth for what exists; the engine is asked separately whose owner is
   * finished.
   */
  @Override
  public List<PersistentVolumeClaim> listWorkspacePVCs() {
    try {
      return client
          .persistentVolumeClaims()
          .withLabels(helperKubeService.getBaseLabels("workspace"))
          .list()
          .getItems();
    } catch (KubernetesClientException e) {
      // Best effort, like the lease heartbeat: the claims stay held and the next tick re-lists.
      LOGGER.warn("Unable to list workspace PersistentVolumeClaims: {}", e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<HeldWorkspace> held() {
    return listWorkspacePVCs().stream()
        .map(
            claim ->
                (claim.getMetadata() != null && claim.getMetadata().getLabels() != null)
                    ? claim.getMetadata().getLabels()
                    : Map.<String, String>of())
        .map(
            labels ->
                new HeldWorkspace(
                    labels.get("boomerang.io/workspace-ref"),
                    labels.get("boomerang.io/workspace-type")))
        .toList();
  }

  @Override
  public void delete(String workspaceRef, String workspaceType) {
    deletePVC(helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null));
  }

  private void deletePVC(Map<String, String> labels) {

    LOGGER.debug("Deleting PersistentVolumeClaim...");

    LOGGER.debug(client.persistentVolumeClaims().list().toString());

    client.persistentVolumeClaims().withLabels(labels).delete();
  }

  protected Boolean isTaskRunResultTooLarge(Map<String, String> labels) {
    try {
      List<Pod> pods = client.pods().withLabels(labels).list().getItems();

      if (pods != null && !pods.isEmpty()) {
        Pod pod = pods.get(0);
        //        LogWatch watch =
        // client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).tailingLines(10).watchLog(out);
        String lastLine =
            client
                .pods()
                .inNamespace(pod.getMetadata().getNamespace())
                .withName(pod.getMetadata().getName())
                .tailingLines(1)
                .getLog();
        ;
        if (lastLine.contains("Termination message is above max allowed size 4096")) {
          return Boolean.TRUE;
        }
      }
    } catch (Exception e) {
      return Boolean.FALSE;
    }
    return Boolean.FALSE;
  }
}
