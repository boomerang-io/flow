package io.boomerang.dispatcher;

import io.boomerang.dispatcher.model.HeldWorkspace;
import java.util.List;
import java.util.Map;

/**
 * The shared storage a workspace is backed by, in whatever runtime the dispatcher drives - a
 * PersistentVolumeClaim on Kubernetes, a named volume on a Docker host. Exactly one implementation
 * is active per deployment, chosen by {@code dispatcher.executor}, and it carries the same labels
 * in either runtime so {@link WorkspaceReconciler} releases storage through one code path.
 */
public interface WorkspaceStore {

  /**
   * Provision the storage for a workspace. {@code className} and {@code accessMode} are Kubernetes
   * storage concepts; a runtime without them ignores them.
   */
  void create(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws InterruptedException;

  /** True when storage for this workspace already exists. */
  boolean exists(String workspaceRef, String workspaceType);

  /** Release the storage. Deleting what is already gone is a no-op. */
  void delete(String workspaceRef, String workspaceType);

  /**
   * Every workspace this dispatcher holds storage for, whoever created it. The runtime is the
   * source of truth for what exists; the engine is asked separately whose owner is finished.
   */
  List<HeldWorkspace> held();
}
