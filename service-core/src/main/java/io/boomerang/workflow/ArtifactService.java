package io.boomerang.workflow;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskRun;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/*
 * Files that upload tasks attach to a run. An artifact's record is written when its upload task is
 * handed to a dispatcher, verified against the stored file when the upload completes, and turned expired - its file
 * deleted, its record kept - when retention ends. Only a delete or the workflow's prune removes the
 * record. Storage limits are checked once the file is in the store, because the link is issued
 * before the task has produced it: an upload that breaks a limit has its own file deleted and its
 * task fails, and no existing artifact is ever deleted to make room.
 *
 * A workflow-scoped artifact is the workflow's kept state under a name: any run of the workflow
 * downloads the one available record of that name, it never expires on its own, and the next
 * verified upload of the name from any run replaces it - the previous record is expired (file
 * deleted, record kept) only once the new file is verified, so a failed replacement leaves the old
 * one in place. The uploading run still owns the record, so the run's own list and download routes
 * serve it like any other.
 */
@Service
public class ArtifactService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final String ARTIFACTS_SETTINGS_KEY = "artifacts";
  public static final String RETENTION_DEFAULT_DAYS = "retention.default.days";
  public static final String RETENTION_MAX_DAYS = "retention.max.days";
  public static final String MAX_ARTIFACT_SIZE = "max.artifact.size";
  /** Whether task logs dispatchers send are stored, and for how many days; both admin settings. */
  public static final String LOGS_ENABLED = "logs.enabled";
  public static final String LOG_RETENTION_DAYS = "logs.retention.days";
  private static final int LOG_RETENTION_DEFAULT_DAYS = 7;

  // The upload and download task params; Flow fills the link ones when the task is handed out.
  public static final String NAME_PARAM = "name";
  public static final String RETENTION_PARAM = "retention-days";
  public static final String SCOPE_PARAM = "scope";
  public static final String OPTIONAL_PARAM = "optional";
  public static final String URL_PARAM = "url";
  public static final String HEADERS_PARAM = "headers";
  public static final String SHA256_PARAM = "sha256";
  public static final String CONTENT_TYPE_PARAM = "contentType";
  /** The download task's result: true once a file was downloaded, false on an optional miss. */
  public static final String FOUND_RESULT = "found";

  /** The content type a stored task log is written with. */
  public static final String LOG_CONTENT_TYPE = "text/plain; charset=utf-8";

  /** What a hand-out resolved to: a link to dispatch, or - for an optional download - nothing. */
  public enum LinkOutcome {
    FILLED,
    NOT_FOUND
  }

  private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final List<ArtifactStatus> HOLDING_STORAGE =
      List.of(ArtifactStatus.uploading, ArtifactStatus.available);

  private final ArtifactRepository artifactRepository;
  private final ObjectProvider<ArtifactStore> artifactStore;
  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final WorkflowService workflowService;
  private final ObjectProvider<WorkspaceService> workspaceService;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;
  private final MessageSource messageSource;
  private final Duration linkTtl;

  public ArtifactService(
      ArtifactRepository artifactRepository,
      ObjectProvider<ArtifactStore> artifactStore,
      SettingsService settingsService,
      RelationshipService relationshipService,
      WorkflowService workflowService,
      ObjectProvider<WorkspaceService> workspaceService,
      MongoTemplate mongoTemplate,
      ObjectMapper objectMapper,
      MessageSource messageSource,
      @Value("${flow.artifacts.link-ttl-minutes:15}") long linkTtlMinutes) {
    this.artifactRepository = artifactRepository;
    this.artifactStore = artifactStore;
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.workflowService = workflowService;
    this.workspaceService = workspaceService;
    this.mongoTemplate = mongoTemplate;
    this.objectMapper = objectMapper;
    this.messageSource = messageSource;
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

  // The log settings were added after the artifact ones: a settings document built before they
  // existed has no entry for them, and reads as the seed's defaults until it is rebuilt.
  private String settingOr(String name, String fallback) {
    try {
      String value = settingsService.getSettingConfig(ARTIFACTS_SETTINGS_KEY, name).getValue();
      return (value == null || value.isBlank()) ? fallback : value.trim();
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  /** Whether task logs dispatchers send are stored (the `logs.enabled` setting, on by default). */
  public boolean logsEnabled() {
    return Boolean.parseBoolean(settingOr(LOGS_ENABLED, "true"));
  }

  /** Days a stored task log is kept (the `logs.retention.days` setting), never less than one. */
  int logRetentionDays() {
    try {
      return Math.max(
          1, Integer.parseInt(settingOr(LOG_RETENTION_DAYS, String.valueOf(LOG_RETENTION_DEFAULT_DAYS))));
    } catch (NumberFormatException e) {
      return LOG_RETENTION_DEFAULT_DAYS;
    }
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

  /** Bytes held by the given workflows' available and uploading artifacts; task logs not counted. */
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
                            .in(HOLDING_STORAGE.stream().map(Enum::name).toList())
                            .and("scope")
                            .ne(ArtifactScope.log.name())),
                    group().sum("size").as("total")),
                ArtifactEntity.class,
                Document.class)
            .getUniqueMappedResult();
    return total == null ? 0 : ((Number) total.get("total")).longValue();
  }

  // ── The upload and download tasks ─────────────────────────────────────────

  /**
   * Fill an artifact task's link params on the TaskRun being handed to a dispatcher, so the worker
   * receives them as ordinary params and nobody else holds store credentials. The values are not
   * persisted: each hand-out, including a requeue, gets a fresh link.
   *
   * <p>Upload: records the artifact as uploading (or reuses this task's own uploading record on a
   * requeue) and fills {@code url} and {@code headers}. A bad name or scope, a name already used in
   * the run, a retention with scope workflow, or a workspace whose storage is already full is
   * refused. Download: fills {@code url}, {@code headers}, {@code sha256} and {@code contentType}
   * for an available artifact - the run's own, or with scope workflow the workflow's kept one of
   * that name. No such artifact is refused, unless the task is {@code optional}: then nothing is
   * filled and the outcome says so, for the caller to end the task without dispatching it.
   */
  public LinkOutcome fillLinkParams(TaskRun task) {
    ArtifactStore store = requireStore();
    String name = requiredName(task.getParams());
    ArtifactScope scope = scopeParam(task.getParams(), name);
    if (TaskType.uploadartifact.equals(task.getType())) {
      ArtifactEntity artifact = uploadingArtifact(task, name, scope);
      ArtifactStore.Link link = store.uploadLink(artifact.getStorageKey(), linkTtl);
      setParam(task, URL_PARAM, link.url().toString());
      setParam(task, HEADERS_PARAM, headersJson(link.headers()));
    } else if (TaskType.downloadartifact.equals(task.getType())) {
      Optional<ArtifactEntity> found =
          (scope == ArtifactScope.workflow)
              ? artifactRepository.findByWorkflowRefAndNameAndScopeAndStatus(
                  task.getWorkflowRef(), name, ArtifactScope.workflow, ArtifactStatus.available)
              : ownedByRun(task.getWorkflowRunRef(), name);
      if (found.isEmpty() && Boolean.parseBoolean(paramValue(task.getParams(), OPTIONAL_PARAM))) {
        return LinkOutcome.NOT_FOUND;
      }
      ArtifactEntity artifact = availableArtifact(found, name);
      ArtifactStore.Link link = store.downloadLink(artifact.getStorageKey(), linkTtl);
      setParam(task, URL_PARAM, link.url().toString());
      setParam(task, HEADERS_PARAM, headersJson(link.headers()));
      setParam(task, SHA256_PARAM, artifact.getSha256());
      setParam(task, CONTENT_TYPE_PARAM, artifact.getContentType());
    }
    return LinkOutcome.FILLED;
  }

  private ArtifactEntity uploadingArtifact(TaskRun task, String name, ArtifactScope scope) {
    if (!NAME.matcher(name).matches()) {
      throw new BoomerangException(BoomerangError.ARTIFACT_INVALID_NAME, name);
    }
    String retention = paramValue(task.getParams(), RETENTION_PARAM);
    if (scope == ArtifactScope.workflow && retention != null && !retention.isBlank()) {
      throw new BoomerangException(
          BoomerangError.ARTIFACT_INVALID_SCOPE,
          name,
          RETENTION_PARAM + " cannot be set with scope workflow.");
    }
    Optional<ArtifactEntity> existing =
        artifactRepository.findByWorkflowRunRefAndName(task.getWorkflowRunRef(), name);
    if (existing.isPresent()) {
      // A requeued upload task gets a fresh link to its own uploading record.
      if (existing.get().getStatus() == ArtifactStatus.uploading
          && task.getId().equals(existing.get().getTaskRunRef())) {
        return existing.get();
      }
      throw new BoomerangException(BoomerangError.ARTIFACT_ALREADY_EXISTS, name);
    }
    CurrentQuotas quotas = quotasFor(task.getWorkflowRunRef());
    if (quotas != null
        && quotas.getCurrentArtifactStorage() >= quotas.getMaxArtifactStorage() * GIB) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Artifact storage (bytes)",
          quotas.getCurrentArtifactStorage(),
          quotas.getMaxArtifactStorage() * GIB);
    }

    Date now = new Date();
    ArtifactEntity artifact = new ArtifactEntity();
    artifact.setName(name);
    artifact.setWorkflowRef(task.getWorkflowRef());
    artifact.setWorkflowRunRef(task.getWorkflowRunRef());
    artifact.setTaskRunRef(task.getId());
    artifact.setScope(scope);
    artifact.setStatus(ArtifactStatus.uploading);
    artifact.setCreationDate(now);
    if (scope == ArtifactScope.workflow) {
      // Its own key per upload, so a replacement never overwrites the kept file before it is
      // verified; no expiry, it is replaced instead.
      artifact.setId(new ObjectId().toHexString());
      artifact.setStorageKey(
          "workflows/" + task.getWorkflowRef() + "/" + name + "/" + artifact.getId());
    } else {
      int retentionDays = resolveRetentionDays(retentionParam(task.getParams()), quotas);
      artifact.setStorageKey("runs/" + task.getWorkflowRunRef() + "/" + name);
      artifact.setRetentionDays(retentionDays);
      artifact.setExpirationDate(new Date(now.getTime() + TimeUnit.DAYS.toMillis(retentionDays)));
    }
    try {
      return artifactRepository.save(artifact);
    } catch (DuplicateKeyException e) {
      throw new BoomerangException(BoomerangError.ARTIFACT_ALREADY_EXISTS, name);
    }
  }

  /**
   * Verify a succeeded upload task's file against the store and make the artifact available. Its
   * size and SHA-256 are read from the stored file, not trusted from the task. A file over the
   * largest-artifact limit, or one the workspace's storage has no room for, is deleted with its
   * record and refused, so the task ends failed with the reason. Completing twice is a no-op.
   *
   * <p>A workflow-scoped artifact replaces the workflow's available one of the same name: that one
   * is expired first (file deleted, record kept), then this one is made available, so the later
   * verified completion wins and every version stays as a record. It gets no expiry date.
   */
  public Artifact completeUpload(TaskRunEntity taskRun) {
    ArtifactStore store = requireStore();
    String name = requiredName(taskRun.getParams());
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
    artifact.setStatus(ArtifactStatus.available);
    if (artifact.scopeOrRun() != ArtifactScope.workflow) {
      artifact.setExpirationDate(
          new Date(now.getTime() + TimeUnit.DAYS.toMillis(artifact.getRetentionDays())));
      return new Artifact(artifactRepository.save(artifact));
    }
    artifact.setExpirationDate(null);
    // Another run's completion of the same name can be verified between expiring the previous
    // record and saving this one; the partial unique index refuses the save, and this later
    // completion then replaces that one. Bounded so a storm of completions cannot spin here.
    DuplicateKeyException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      replacePrevious(store, artifact);
      try {
        return new Artifact(artifactRepository.save(artifact));
      } catch (DuplicateKeyException e) {
        last = e;
      }
    }
    throw last;
  }

  /** Expire the workflow's available artifact of this name, unless it is this record. */
  private void replacePrevious(ArtifactStore store, ArtifactEntity artifact) {
    artifactRepository
        .findByWorkflowRefAndNameAndScopeAndStatus(
            artifact.getWorkflowRef(),
            artifact.getName(),
            ArtifactScope.workflow,
            ArtifactStatus.available)
        .filter(previous -> !previous.getId().equals(artifact.getId()))
        .ifPresent(previous -> expire(store, previous));
  }

  /** Whether the task is one of the two artifact tasks. */
  public static boolean isArtifactTask(TaskType type) {
    return TaskType.uploadartifact.equals(type) || TaskType.downloadartifact.equals(type);
  }

  /** A refusal as the task's status message: the same text the API would answer with. */
  public String refusalMessage(BoomerangException e) {
    return messageSource.getMessage(e.getReason(), e.getArgs(), e.getReason(), Locale.ENGLISH);
  }

  // ── Task logs ─────────────────────────────────────────────────────────────

  /**
   * Store the finished log a dispatcher sent for a task as the task's log artifact: scope {@code
   * log}, named after the task run, kept for the log retention setting, available at once. A second
   * upload for the same task run - a requeued attempt - replaces the first, file and record. A log
   * is read only through the task run's log route, is not listed with the run's artifacts and does
   * not count toward the workspace's artifact storage. Refused with {@code TASKRUN_LOGS_DISABLED}
   * when the setting is off, and with {@code ARTIFACTS_NOT_CONFIGURED} when there is no store.
   */
  public void storeTaskLog(TaskRunEntity taskRun, byte[] content) {
    ArtifactStore store = requireStore();
    if (!logsEnabled()) {
      throw new BoomerangException(BoomerangError.TASKRUN_LOGS_DISABLED);
    }
    String name = logName(taskRun.getId());
    Date now = new Date();
    int retentionDays = logRetentionDays();
    ArtifactEntity artifact =
        artifactRepository
            .findByWorkflowRunRefAndName(taskRun.getWorkflowRunRef(), name)
            .orElseGet(ArtifactEntity::new);
    artifact.setName(name);
    artifact.setWorkflowRef(taskRun.getWorkflowRef());
    artifact.setWorkflowRunRef(taskRun.getWorkflowRunRef());
    artifact.setTaskRunRef(taskRun.getId());
    artifact.setScope(ArtifactScope.log);
    artifact.setStorageKey("runs/" + taskRun.getWorkflowRunRef() + "/logs/" + taskRun.getId());
    artifact.setSize(content.length);
    artifact.setContentType(LOG_CONTENT_TYPE);
    artifact.setSha256(sha256(content));
    artifact.setRetentionDays(retentionDays);
    artifact.setCreationDate(now);
    artifact.setExpirationDate(new Date(now.getTime() + TimeUnit.DAYS.toMillis(retentionDays)));
    store.put(artifact.getStorageKey(), content, LOG_CONTENT_TYPE);
    artifact.setStatus(ArtifactStatus.available);
    artifactRepository.save(artifact);
  }

  /** The task's stored log, when a dispatcher uploaded one and it has not expired. */
  public Optional<ArtifactEntity> taskLog(TaskRunEntity taskRun) {
    return artifactRepository
        .findByWorkflowRunRefAndName(taskRun.getWorkflowRunRef(), logName(taskRun.getId()))
        .filter(artifact -> artifact.scopeOrRun() == ArtifactScope.log)
        .filter(artifact -> artifact.getStatus() == ArtifactStatus.available);
  }

  /** An open stream of a stored log's file; the caller closes it. */
  public InputStream openLog(ArtifactEntity log) {
    return requireStore().open(log.getStorageKey());
  }

  static String logName(String taskRunId) {
    return taskRunId + ".log";
  }

  // ── The workspace-scoped API ──────────────────────────────────────────────

  public List<Artifact> listForRun(String workspace, String workflowRunId) {
    requireRunInWorkspace(workspace, workflowRunId);
    return artifactRepository.findByWorkflowRunRefOrderByCreationDateAsc(workflowRunId).stream()
        .filter(artifact -> artifact.getStatus() != ArtifactStatus.uploading)
        .filter(artifact -> artifact.scopeOrRun() != ArtifactScope.log)
        .map(Artifact::new)
        .toList();
  }

  /**
   * The artifact and an open stream of its file; the caller closes the stream. A task log is not
   * an artifact here: it is served, scrubbed, by the task run's log route only.
   */
  public ArtifactFile open(String workspace, String workflowRunId, String name) {
    requireRunInWorkspace(workspace, workflowRunId);
    ArtifactEntity artifact =
        availableArtifact(
            ownedByRun(workflowRunId, name)
                .filter(found -> found.scopeOrRun() != ArtifactScope.log),
            name);
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

  /**
   * The workspace's artifacts in the given statuses (available by default), largest first,
   * narrowed to one scope and to one workflow (by name) when asked. A record written before scopes
   * existed is run-scoped. Task logs are listed only when asked for by scope.
   */
  public Page<Artifact> query(
      String workspace,
      Optional<List<ArtifactStatus>> statuses,
      Optional<ArtifactScope> scope,
      Optional<String> workflow,
      int page,
      int limit) {
    Query query =
        Query.query(
            where("workflowRef")
                .in(workspaceWorkflowRefs(workspace, workflow.map(List::of)))
                .and("status")
                .in(
                    statuses
                        .filter(list -> !list.isEmpty())
                        .orElse(List.of(ArtifactStatus.available))));
    query.addCriteria(
        scope
            .map(
                wanted ->
                    switch (wanted) {
                      case workflow -> where("scope").is(ArtifactScope.workflow);
                      case log -> where("scope").is(ArtifactScope.log);
                      case run -> where("scope").nin(ArtifactScope.workflow, ArtifactScope.log);
                    })
            .orElse(where("scope").ne(ArtifactScope.log)));
    Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "size"));
    return PageableExecutionUtils.getPage(
            mongoTemplate.find(query.with(pageable), ArtifactEntity.class),
            pageable,
            () -> mongoTemplate.count(Query.of(query).skip(-1).limit(-1), ArtifactEntity.class))
        .map(Artifact::new);
  }

  public void deleteById(String workspace, String artifactId) {
    ArtifactEntity artifact =
        artifactRepository
            .findById(artifactId)
            .filter(
                found ->
                    workspaceWorkflowRefs(workspace, Optional.empty())
                        .contains(found.getWorkflowRef()))
            .orElseThrow(
                () -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, artifactId));
    remove(artifact);
  }

  // ── The watcher's sweeps ──────────────────────────────────────────────────

  /**
   * Expire artifacts past their expiration date. A workflow-scoped artifact has none and is only
   * ever expired by its replacement.
   */
  public void expireArtifacts(int pageSize) {
    ArtifactStore store = artifactStore.getIfAvailable();
    if (store == null) {
      return;
    }
    artifactRepository
        .findByStatusAndExpirationDateBefore(
            ArtifactStatus.available, new Date(), PageRequest.of(0, pageSize))
        .forEach(artifact -> expire(store, artifact));
  }

  /**
   * Delete the file, then compare-and-set the record from available to expired. A crash between
   * the two leaves an available record whose file is gone; the next pass deletes again (a no-op)
   * and completes the transition.
   */
  private void expire(ArtifactStore store, ArtifactEntity artifact) {
    store.delete(artifact.getStorageKey());
    mongoTemplate.updateFirst(
        Query.query(where("_id").is(artifact.getId()).and("status").is("available")),
        Update.update("status", ArtifactStatus.expired.name()),
        ArtifactEntity.class);
    LOGGER.info(
        "[{}] Artifact {} expired: its file is deleted, its record kept.",
        artifact.getWorkflowRunRef(),
        artifact.getName());
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

  /**
   * Delete a purged run's own artifacts and task logs, files first. A workflow-scoped artifact
   * the run uploaded is the workflow's state and stays.
   */
  public void deleteForRun(String workflowRunRef) {
    artifactRepository
        .findByWorkflowRunRefAndScopeNot(workflowRunRef, ArtifactScope.workflow)
        .forEach(this::remove);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private ArtifactStore requireStore() {
    ArtifactStore store = artifactStore.getIfAvailable();
    if (store == null) {
      throw new BoomerangException(BoomerangError.ARTIFACTS_NOT_CONFIGURED);
    }
    return store;
  }

  /** The run's own record of the name, once its upload has completed. */
  private Optional<ArtifactEntity> ownedByRun(String workflowRunId, String name) {
    return artifactRepository
        .findByWorkflowRunRefAndName(workflowRunId, name)
        .filter(found -> found.getStatus() != ArtifactStatus.uploading);
  }

  /** The found artifact if its file can be served; none is refused, as is an expired one. */
  private static ArtifactEntity availableArtifact(Optional<ArtifactEntity> found, String name) {
    ArtifactEntity artifact =
        found.orElseThrow(() -> new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, name));
    if (artifact.getStatus() == ArtifactStatus.expired) {
      // A workflow-scoped record has no date: it expired when its replacement was verified.
      throw new BoomerangException(
          BoomerangError.ARTIFACT_EXPIRED,
          name,
          (artifact.getExpirationDate() != null) ? artifact.getExpirationDate() : "replacement");
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

  /** The workspace's workflow refs the caller may see, narrowed to the named ones when given. */
  private List<String> workspaceWorkflowRefs(String workspace, Optional<List<String>> names) {
    return relationshipService.filter(
        RelationshipType.WORKFLOW,
        names,
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(workspace)),
        false);
  }

  /**
   * The run's workspace artifact quota, or null when quotas are not enforced or no workspace owns
   * it. Read without a caller identity: the dispatcher's poll and end calls act for no user.
   */
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
    return workspaceService.getObject().getArtifactStorageQuota(workspaceRef);
  }

  private static String requiredName(List<RunParam> params) {
    String name = paramValue(params, NAME_PARAM);
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.ARTIFACT_INVALID_NAME, name);
    }
    return name;
  }

  /** The task's scope param: absent or blank is run; anything but run or workflow is refused. */
  private static ArtifactScope scopeParam(List<RunParam> params, String name) {
    String value = paramValue(params, SCOPE_PARAM);
    if (value == null || value.isBlank()) {
      return ArtifactScope.run;
    }
    try {
      ArtifactScope scope = ArtifactScope.valueOf(value.trim());
      if (scope == ArtifactScope.log) {
        throw new IllegalArgumentException(value);
      }
      return scope;
    } catch (IllegalArgumentException e) {
      throw new BoomerangException(
          BoomerangError.ARTIFACT_INVALID_SCOPE,
          name,
          "Scope must be run or workflow, not \"" + value + "\".");
    }
  }

  private static Integer retentionParam(List<RunParam> params) {
    String value = paramValue(params, RETENTION_PARAM);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String paramValue(List<RunParam> params, String name) {
    if (params == null) {
      return null;
    }
    return params.stream()
        .filter(param -> name.equals(param.getName()) && param.getValue() != null)
        .map(param -> param.getValue().toString())
        .findFirst()
        .orElse(null);
  }

  /** Replace the param's value on the handed-out TaskRun, adding it when it was not declared. */
  private static void setParam(TaskRun task, String name, String value) {
    List<RunParam> params =
        new ArrayList<>(task.getParams() != null ? task.getParams() : List.of());
    params.removeIf(param -> name.equals(param.getName()));
    params.add(new RunParam(name, value));
    task.setParams(params);
  }

  private String headersJson(Map<String, String> headers) {
    return objectMapper.writeValueAsString(headers != null ? headers : Map.of());
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
