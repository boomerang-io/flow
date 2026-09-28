package io.boomerang.workflow;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.ArtifactLink;
import io.boomerang.common.model.ArtifactUploadRequest;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.workflow.model.Artifact;
import io.boomerang.workflow.repository.ArtifactRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/*
 * Files that upload tasks attach to a run. An artifact's record is written when its upload link is
 * issued, verified against the stored file when the upload completes, and turned expired - its file
 * deleted, its record kept - when retention ends. Only a delete or the workflow's prune removes the
 * record. Storage limits are checked once the file is in the store, because the link is issued
 * before the task has produced it: an upload that breaks a limit has its own file deleted and its
 * task fails, and no existing artifact is ever deleted to make room.
 */
@Service
public class ArtifactService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final String ARTIFACTS_SETTINGS_KEY = "artifacts";
  public static final String RETENTION_DEFAULT_DAYS = "retention.default.days";
  public static final String RETENTION_MAX_DAYS = "retention.max.days";
  public static final String MAX_ARTIFACT_SIZE = "max.artifact.size";

  private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final List<ArtifactStatus> HOLDING_STORAGE =
      List.of(ArtifactStatus.uploading, ArtifactStatus.available);

  private final ArtifactRepository artifactRepository;
  private final TaskRunRepository taskRunRepository;
  private final ObjectProvider<ArtifactStore> artifactStore;
  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final WorkflowService workflowService;
  private final ObjectProvider<WorkspaceService> workspaceService;
  private final MongoTemplate mongoTemplate;
  private final Duration linkTtl;

  public ArtifactService(
      ArtifactRepository artifactRepository,
      TaskRunRepository taskRunRepository,
      ObjectProvider<ArtifactStore> artifactStore,
      SettingsService settingsService,
      RelationshipService relationshipService,
      WorkflowService workflowService,
      ObjectProvider<WorkspaceService> workspaceService,
      MongoTemplate mongoTemplate,
      @Value("${flow.artifacts.link-ttl-minutes:15}") long linkTtlMinutes) {
    this.artifactRepository = artifactRepository;
    this.taskRunRepository = taskRunRepository;
    this.artifactStore = artifactStore;
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.workflowService = workflowService;
    this.workspaceService = workspaceService;
    this.mongoTemplate = mongoTemplate;
    this.linkTtl = Duration.ofMinutes(linkTtlMinutes);
  }

  // ── Limits ────────────────────────────────────────────────────────────────

  /** The retention a workspace gets unless its quota says otherwise, never above the maximum. */
  public int defaultRetentionDays() {
    return Math.min(setting(RETENTION_DEFAULT_DAYS), setting(RETENTION_MAX_DAYS));
  }

  private long maxArtifactBytes() {
    return setting(MAX_ARTIFACT_SIZE) * MIB;
  }

  private int setting(String name) {
    return Integer.parseInt(
        settingsService.getSettingConfig(ARTIFACTS_SETTINGS_KEY, name).getValue().trim());
  }

  /*
   * Retention = the task's request when set, otherwise the workspace's; never more than the
   * workspace's, never more than the platform maximum, never less than a day.
   */
  private int resolveRetentionDays(Integer requested, CurrentQuotas quotas) {
    int workspace =
        (quotas != null && quotas.getArtifactRetentionDays() != null)
            ? quotas.getArtifactRetentionDays()
            : defaultRetentionDays();
    int days = (requested != null && requested > 0) ? Math.min(requested, workspace) : workspace;
    return Math.max(1, Math.min(days, setting(RETENTION_MAX_DAYS)));
  }

  /** Bytes held by the given workflows' available and uploading artifacts. */
  public long storedBytes(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return 0;
    }
    Document total =
        mongoTemplate
            .aggregate(
                newAggregation(
                    match(
                        where("workflowRef")
                            .in(workflowRefs)
                            .and("status")
                            .in(HOLDING_STORAGE.stream().map(Enum::name).toList())),
                    group().sum("size").as("total")),
                ArtifactEntity.class,
                Document.class)
            .getUniqueMappedResult();
    return total == null ? 0 : ((Number) total.get("total")).longValue();
  }

  // ── The dispatcher's calls, on behalf of the upload and download tasks ────

  /**
   * Record an artifact as uploading and return the link its task PUTs the file to. A name already
   * used in the run is refused, as is any upload while the workspace's storage is already full.
   */
  public ArtifactLink beginUpload(String taskRunId, ArtifactUploadRequest request) {
    ArtifactStore store = requireStore();
    TaskRunEntity taskRun = claimedTaskRun(taskRunId, request.dispatcherRef());
    String name = request.name();
    if (name == null || !NAME.matcher(name).matches()) {
      throw new BoomerangException(BoomerangError.ARTIFACT_INVALID_NAME, name);
    }
    // The unique {workflowRunRef, name} index is the guard against a race; this is the answer.
    if (artifactRepository.existsByWorkflowRunRefAndName(taskRun.getWorkflowRunRef(), name)) {
      throw new BoomerangException(BoomerangError.ARTIFACT_ALREADY_EXISTS, name);
    }
    CurrentQuotas quotas = quotasFor(taskRun.getWorkflowRunRef());
    if (quotas != null
        && quotas.getCurrentArtifactStorage() >= quotas.getMaxArtifactStorage() * GIB) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Artifact storage (bytes)",
          quotas.getCurrentArtifactStorage(),
          quotas.getMaxArtifactStorage() * GIB);
    }

    Date now = new Date();
    int retentionDays = resolveRetentionDays(request.retentionDays(), quotas);
    ArtifactEntity artifact = new ArtifactEntity();
    artifact.setName(name);
    artifact.setWorkflowRef(taskRun.getWorkflowRef());
    artifact.setWorkflowRunRef(taskRun.getWorkflowRunRef());
    artifact.setTaskRunRef(taskRun.getId());
    artifact.setStorageKey("runs/" + taskRun.getWorkflowRunRef() + "/" + name);
    artifact.setStatus(ArtifactStatus.uploading);
    artifact.setCreationDate(now);
    artifact.setRetentionDays(retentionDays);
    artifact.setExpirationDate(new Date(now.getTime() + TimeUnit.DAYS.toMillis(retentionDays)));
    try {
      artifactRepository.save(artifact);
    } catch (DuplicateKeyException e) {
      throw new BoomerangException(BoomerangError.ARTIFACT_ALREADY_EXISTS, name);
    }
    ArtifactStore.Link link = store.uploadLink(artifact.getStorageKey(), linkTtl);
    return new ArtifactLink(
        name, link.url().toString(), link.headers(), new Date(now.getTime() + linkTtl.toMillis()), null);
  }

  /**
   * Verify an upload against the store and make the artifact available. Its size and SHA-256 are
   * read from the stored file, not trusted from the task. A file over the largest-artifact limit,
   * or one the workspace's storage has no room for, is deleted with its record and the call is
   * refused, so the upload task fails with the reason. Completing twice returns the same artifact.
   */
  public Artifact completeUpload(String taskRunId, String name, String dispatcherRef) {
    ArtifactStore store = requireStore();
    TaskRunEntity taskRun = claimedTaskRun(taskRunId, dispatcherRef);
    ArtifactEntity artifact =
        artifactRepository
            .findByWorkflowRunRefAndName(taskRun.getWorkflowRunRef(), name)
            .orElseThrow(() -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, name));
    if (artifact.getStatus() != ArtifactStatus.uploading) {
      return new Artifact(artifact);
    }
    ArtifactStore.StoredObject stored =
        store
            .head(artifact.getStorageKey())
            .orElseThrow(() -> new BoomerangException(BoomerangError.ARTIFACT_NOT_UPLOADED, name));
    if (stored.size() > maxArtifactBytes()) {
      discard(store, artifact);
      throw new BoomerangException(
          BoomerangError.ARTIFACT_TOO_LARGE, name, stored.size(), maxArtifactBytes());
    }
    CurrentQuotas quotas = quotasFor(taskRun.getWorkflowRunRef());
    if (quotas != null
        && quotas.getCurrentArtifactStorage() + stored.size()
            > quotas.getMaxArtifactStorage() * GIB) {
      discard(store, artifact);
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Artifact storage (bytes)",
          quotas.getCurrentArtifactStorage() + stored.size(),
          quotas.getMaxArtifactStorage() * GIB);
    }

    Date now = new Date();
    artifact.setSize(stored.size());
    artifact.setContentType(stored.contentType());
    artifact.setSha256(sha256(store, artifact.getStorageKey()));
    // Retention runs from the moment the file is available, not from when the link was issued.
    artifact.setCreationDate(now);
    artifact.setExpirationDate(
        new Date(now.getTime() + TimeUnit.DAYS.toMillis(artifact.getRetentionDays())));
    artifact.setStatus(ArtifactStatus.available);
    return new Artifact(artifactRepository.save(artifact));
  }

  /** A download link for an artifact of the task's own run, with the SHA-256 to check it against. */
  public ArtifactLink downloadLink(String taskRunId, String name, String dispatcherRef) {
    ArtifactStore store = requireStore();
    TaskRunEntity taskRun = claimedTaskRun(taskRunId, dispatcherRef);
    ArtifactEntity artifact = availableArtifact(taskRun.getWorkflowRunRef(), name);
    ArtifactStore.Link link = store.downloadLink(artifact.getStorageKey(), linkTtl);
    return new ArtifactLink(
        name,
        link.url().toString(),
        link.headers(),
        new Date(System.currentTimeMillis() + linkTtl.toMillis()),
        artifact.getSha256());
  }

  // ── The workspace-scoped API ──────────────────────────────────────────────

  public List<Artifact> listForRun(String workspace, String workflowRunId) {
    requireRunInWorkspace(workspace, workflowRunId);
    return artifactRepository.findByWorkflowRunRefOrderByCreationDateAsc(workflowRunId).stream()
        .filter(artifact -> artifact.getStatus() != ArtifactStatus.uploading)
        .map(Artifact::new)
        .toList();
  }

  /** The artifact and an open stream of its file; the caller closes the stream. */
  public ArtifactFile open(String workspace, String workflowRunId, String name) {
    requireRunInWorkspace(workspace, workflowRunId);
    ArtifactEntity artifact = availableArtifact(workflowRunId, name);
    return new ArtifactFile(new Artifact(artifact), requireStore().open(artifact.getStorageKey()));
  }

  public void delete(String workspace, String workflowRunId, String name) {
    requireRunInWorkspace(workspace, workflowRunId);
    ArtifactEntity artifact =
        artifactRepository
            .findByWorkflowRunRefAndName(workflowRunId, name)
            .orElseThrow(() -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, name));
    remove(artifact);
  }

  /** The workspace's artifacts in the given statuses (available by default), largest first. */
  public Page<Artifact> query(
      String workspace, Optional<List<ArtifactStatus>> statuses, int page, int limit) {
    return artifactRepository
        .findByWorkflowRefInAndStatusIn(
            workspaceWorkflowRefs(workspace),
            statuses.filter(list -> !list.isEmpty()).orElse(List.of(ArtifactStatus.available)),
            PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "size")))
        .map(Artifact::new);
  }

  public void deleteById(String workspace, String artifactId) {
    ArtifactEntity artifact =
        artifactRepository
            .findById(artifactId)
            .filter(found -> workspaceWorkflowRefs(workspace).contains(found.getWorkflowRef()))
            .orElseThrow(
                () -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, artifactId));
    remove(artifact);
  }

  // ── The watcher's sweeps ──────────────────────────────────────────────────

  /**
   * Expire artifacts past their expiration date: delete the file, then compare-and-set the record
   * from available to expired. A crash between the two leaves an available record whose file is
   * gone; the next sweep deletes again (a no-op) and completes the transition.
   */
  public void expireArtifacts(int pageSize) {
    ArtifactStore store = artifactStore.getIfAvailable();
    if (store == null) {
      return;
    }
    artifactRepository
        .findByStatusAndExpirationDateBefore(
            ArtifactStatus.available, new Date(), PageRequest.of(0, pageSize))
        .forEach(
            artifact -> {
              store.delete(artifact.getStorageKey());
              mongoTemplate.updateFirst(
                  Query.query(where("_id").is(artifact.getId()).and("status").is("available")),
                  Update.update("status", ArtifactStatus.expired.name()),
                  ArtifactEntity.class);
              LOGGER.info(
                  "[{}] Artifact {} expired: its file is deleted, its record kept.",
                  artifact.getWorkflowRunRef(),
                  artifact.getName());
            });
  }

  /** Remove uploads whose task never completed them within the grace period. */
  public void reapStaleUploads(Duration grace, int pageSize) {
    ArtifactStore store = artifactStore.getIfAvailable();
    if (store == null) {
      return;
    }
    artifactRepository
        .findByStatusAndCreationDateBefore(
            ArtifactStatus.uploading,
            new Date(System.currentTimeMillis() - grace.toMillis()),
            PageRequest.of(0, pageSize))
        .forEach(artifact -> discard(store, artifact));
  }

  /** Delete every artifact of a pruned workflow, files first. */
  public void deleteForWorkflow(String workflowRef, int pageSize) {
    List<ArtifactEntity> page;
    do {
      page = artifactRepository.findByWorkflowRef(workflowRef, PageRequest.of(0, pageSize));
      page.forEach(this::remove);
    } while (page.size() == pageSize);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private ArtifactStore requireStore() {
    ArtifactStore store = artifactStore.getIfAvailable();
    if (store == null) {
      throw new BoomerangException(BoomerangError.ARTIFACTS_NOT_CONFIGURED);
    }
    return store;
  }

  private TaskRunEntity claimedTaskRun(String taskRunId, String dispatcherRef) {
    TaskRunEntity taskRun =
        taskRunRepository
            .findById(taskRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.TASKRUN_INVALID_REF));
    TaskRunService.rejectSupersededClaimant(taskRun, Optional.ofNullable(dispatcherRef));
    return taskRun;
  }

  private ArtifactEntity availableArtifact(String workflowRunId, String name) {
    ArtifactEntity artifact =
        artifactRepository
            .findByWorkflowRunRefAndName(workflowRunId, name)
            .filter(found -> found.getStatus() != ArtifactStatus.uploading)
            .orElseThrow(() -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, name));
    if (artifact.getStatus() == ArtifactStatus.expired) {
      throw new BoomerangException(
          BoomerangError.ARTIFACT_EXPIRED, name, artifact.getExpirationDate());
    }
    return artifact;
  }

  /** Remove an artifact outright: its file (unless already expired), then its record. */
  private void remove(ArtifactEntity artifact) {
    if (artifact.getStatus() != ArtifactStatus.expired) {
      requireStore().delete(artifact.getStorageKey());
    }
    artifactRepository.deleteById(artifact.getId());
  }

  private void discard(ArtifactStore store, ArtifactEntity artifact) {
    store.delete(artifact.getStorageKey());
    artifactRepository.deleteById(artifact.getId());
  }

  private void requireRunInWorkspace(String workspace, String workflowRunId) {
    if (workflowRunId == null
        || workflowRunId.isBlank()
        || !relationshipService.check(
            RelationshipType.WORKFLOWRUN,
            workflowRunId,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)))) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  private List<String> workspaceWorkflowRefs(String workspace) {
    return relationshipService.filter(
        RelationshipType.WORKFLOW,
        Optional.empty(),
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(workspace)),
        false);
  }

  /** The run's workspace quotas, or null when quotas are not enforced or no workspace owns it. */
  private CurrentQuotas quotasFor(String workflowRunId) {
    if (!workflowService.quotasEnforced()) {
      return null;
    }
    String workspaceRef =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, workflowRunId);
    if (workspaceRef == null || workspaceRef.isBlank()) {
      return null;
    }
    return workspaceService
        .getObject()
        .getCurrentQuotas(
            relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, workspaceRef));
  }

  private static String sha256(ArtifactStore store, String key) {
    try (DigestInputStream in =
        new DigestInputStream(store.open(key), MessageDigest.getInstance("SHA-256"))) {
      in.transferTo(OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(in.getMessageDigest().digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** An artifact and an open stream of its file. */
  public record ArtifactFile(Artifact artifact, InputStream content) {}
}
