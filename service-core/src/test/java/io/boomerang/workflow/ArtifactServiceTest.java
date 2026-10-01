package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.model.Artifact;
import io.boomerang.workflow.repository.ArtifactRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Artifacts end to end against a real MongoDB and an in-memory store: the upload handshake the
 * dispatcher drives, the limits (name, largest artifact, storage quota, retention), the
 * workspace-scoped reads, the watcher's expiry and stale-upload sweeps, and the workflow prune. An
 * expired artifact keeps its record and loses its file; nothing is deleted to make room.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class ArtifactServiceTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "artifact-test-task";
  private static final String QUOTA_FEATURE = "workspaceQuotas";

  @Autowired private ArtifactService artifactService;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private InMemoryArtifactStore store;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WebApplicationContext context;

  private String workspace;
  private String workflowRef;
  private TaskRunEntity uploadTask;
  private int uploadTasks;

  @BeforeEach
  void seedRun() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);

    workspace = createWorkspace(new Quotas());
    workflowService.create(workspace, runnableWorkflow("artifact-workflow", TASK_SLUG));
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, "artifact-workflow", request, false);
    workflowRef = run.getWorkflowRef();
    uploadTask =
        savedTaskRun(
            "upload",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            workflowRef,
            run.getId());
  }

  @AfterEach
  void resetSharedSettings() {
    setFeatureSetting(QUOTA_FEATURE, false);
    setArtifactSetting(ArtifactService.MAX_ARTIFACT_SIZE, "1024");
  }

  @Test
  void anUploadIsVerifiedAgainstTheStoreAndBecomesAvailable() throws Exception {
    byte[] bytes = "sbom contents".getBytes(StandardCharsets.UTF_8);

    TaskRun link = upload("sbom.json", null, bytes);
    Artifact artifact = complete("sbom.json");

    assertThat(urlOf(link)).contains("runs/" + uploadTask.getWorkflowRunRef() + "/sbom.json");
    assertThat(link.getParams()).extracting(RunParam::getName).contains("url", "headers");
    assertThat(artifact.status()).isEqualTo(ArtifactStatus.available);
    assertThat(artifact.size()).isEqualTo(bytes.length);
    assertThat(artifact.sha256())
        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    assertThat(artifact.retentionDays()).isEqualTo(30);
    assertThat(artifact.expirationDate())
        .isCloseTo(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)), 60_000);
    assertThat(artifactService.listForRun(workspace, uploadTask.getWorkflowRunRef()))
        .extracting(Artifact::name)
        .containsExactly("sbom.json");
  }

  @Test
  void completingTwiceReturnsTheSameArtifact() {
    upload("twice.txt", null, new byte[] {1, 2, 3});
    Artifact first = complete("twice.txt");

    assertThat(complete("twice.txt"))
        .isEqualTo(first);
  }

  @Test
  void aNameIsUniqueWithinTheRun() {
    upload("report", null, new byte[] {1});

    assertThatThrownBy(() -> begin("report", null))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_ALREADY_EXISTS"));
  }

  @Test
  void aNameWithAPathOrSpacesIsRefused() {
    for (String name : List.of("../escape", "has space", "", ".hidden")) {
      assertThatThrownBy(() -> begin(name, null))
          .as(name)
          .isInstanceOfSatisfying(
              BoomerangException.class,
              ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_NAME"));
    }
  }

  @Test
  void anUploadOverTheLargestArtifactIsDeletedAndRefused() {
    setArtifactSetting(ArtifactService.MAX_ARTIFACT_SIZE, "1");
    TaskRun link = upload("big.bin", null, new byte[2 * 1024 * 1024]);

    assertThatThrownBy(() -> complete("big.bin"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_TOO_LARGE"));
    assertThat(store.objects).doesNotContainKey(keyOf(link));
    assertThat(
            artifactRepository.findByWorkflowRunRefAndName(
                uploadTask.getWorkflowRunRef(), "big.bin"))
        .isEmpty();
  }

  @Test
  void aWorkspaceWithNoArtifactStorageLeftRefusesTheUploadAndKeepsWhatItHas() {
    upload("kept.txt", null, new byte[] {7});
    complete("kept.txt");
    Quotas none = new Quotas();
    none.setMaxArtifactStorage(0);
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(none);
    workspaceService.patch(workspace, patch);
    setFeatureSetting(QUOTA_FEATURE, true);

    assertThatThrownBy(() -> begin("refused.txt", null))
        .isInstanceOfSatisfying(
            BoomerangException.class, ex -> assertThat(ex.getReason()).isEqualTo("QUOTA_EXCEEDED"));
    assertThat(artifactService.listForRun(workspace, uploadTask.getWorkflowRunRef()))
        .extracting(Artifact::name)
        .containsExactly("kept.txt");
  }

  @Test
  void aTaskCanShortenItsRetentionButNeverLengthenIt() {
    begin("short", 3);
    begin("long", 500);

    assertThat(retentionOf("short")).isEqualTo(3);
    assertThat(retentionOf("long")).isEqualTo(30);
  }

  @Test
  void anExpiredArtifactLosesItsFileButKeepsItsRecord() throws Exception {
    TaskRun link = upload("old.log", null, "log".getBytes(StandardCharsets.UTF_8));
    complete("old.log");
    ArtifactEntity entity =
        artifactRepository
            .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), "old.log")
            .orElseThrow();
    entity.setExpirationDate(new Date(System.currentTimeMillis() - 1000));
    artifactRepository.save(entity);

    artifactService.expireArtifacts(100);

    assertThat(store.objects).doesNotContainKey(keyOf(link));
    assertThat(artifactService.listForRun(workspace, uploadTask.getWorkflowRunRef()))
        .singleElement()
        .satisfies(artifact -> assertThat(artifact.status()).isEqualTo(ArtifactStatus.expired));
    assertThatThrownBy(
            () -> artifactService.open(workspace, uploadTask.getWorkflowRunRef(), "old.log"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getStatus().value()).isEqualTo(410));
  }

  @Test
  void anUploadThatWasNeverCompletedIsReaped() {
    TaskRun link = upload("stale", null, new byte[] {1});
    ArtifactEntity entity =
        artifactRepository
            .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), "stale")
            .orElseThrow();
    entity.setCreationDate(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)));
    artifactRepository.save(entity);

    artifactService.reapStaleUploads(Duration.ofMinutes(60), 100);

    assertThat(artifactRepository.findById(entity.getId())).isEmpty();
    assertThat(store.objects).doesNotContainKey(keyOf(link));
  }

  @Test
  void pruningTheWorkflowDeletesItsArtifactsAndFiles() {
    TaskRun link = upload("pruned", null, new byte[] {1});
    complete("pruned");

    artifactService.deleteForWorkflow(workflowRef, 100);

    assertThat(artifactRepository.findByWorkflowRunRefOrderByCreationDateAsc(
            uploadTask.getWorkflowRunRef()))
        .isEmpty();
    assertThat(store.objects).doesNotContainKey(keyOf(link));
  }

  @Test
  void storageCountsAvailableAndUploadingButNotExpired() {
    upload("available", null, new byte[10]);
    complete("available");
    upload("expired", null, new byte[100]);
    complete("expired");
    ArtifactEntity expired =
        artifactRepository
            .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), "expired")
            .orElseThrow();
    expired.setStatus(ArtifactStatus.expired);
    artifactRepository.save(expired);

    assertThat(artifactService.storedBytes(List.of(workflowRef))).isEqualTo(10);
  }

  @Test
  void aMemberCannotReachAnotherWorkspacesRunThroughTheirOwn() {
    // A global identity passes the relationship check for any workspace, so this runs as a
    // member of one workspace addressing a run owned by another.
    String member = "artifact-member-" + UUID.randomUUID().toString().substring(0, 8);
    String mine = member + "-mine";
    String theirs = member + "-theirs";
    relationshipService.createNode(RelationshipType.USER, member, member, Optional.empty());
    relationshipService.createNode(RelationshipType.WORKSPACE, mine, mine, Optional.empty());
    relationshipService.createNode(RelationshipType.WORKSPACE, theirs, theirs, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        member,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        mine,
        Optional.empty());
    WorkflowRunEntity foreignRun =
        savedWorkflowRun("artifact-foreign", RunStatus.running, RunPhase.running);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        theirs,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        foreignRun.getId(),
        foreignRun.getId(),
        Optional.empty(),
        Optional.empty());
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(member);
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, mine, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(member, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThatThrownBy(() -> artifactService.listForRun(mine, foreignRun.getId()))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("WORKFLOWRUN_INVALID_REFERENCE"));
  }

  @Test
  void theDownloadStreamsTheFileAsAnAttachment() throws Exception {
    byte[] bytes = "hello artifact".getBytes(StandardCharsets.UTF_8);
    upload("hello.txt", null, bytes);
    complete("hello.txt");
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

    MvcResult started =
        mockMvc
            .perform(
                get(
                    "/api/v2/workspace/{workspace}/workflowrun/{run}/artifacts/{name}",
                    workspace,
                    uploadTask.getWorkflowRunRef(),
                    "hello.txt"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"hello.txt\""))
        .andExpect(content().bytes(bytes));
  }

  @Test
  void aRequeuedUploadTaskGetsAFreshLinkToItsOwnRecord() {
    TaskRun first = begin("requeued", null);
    TaskRunEntity entity = taskRunRepository.findById(first.getId()).orElseThrow();
    TaskRun again = new TaskRun(entity);

    artifactService.fillLinkParams(again);

    assertThat(urlOf(again)).isEqualTo(urlOf(first));
    assertThat(
            artifactRepository.findByWorkflowRunRefAndName(
                uploadTask.getWorkflowRunRef(), "requeued"))
        .isPresent();
  }

  @Test
  void aDownloadTaskIsHandedTheLinkChecksumAndContentType() {
    upload("for-download", null, "payload".getBytes(StandardCharsets.UTF_8));
    Artifact artifact = complete("for-download");
    TaskRunEntity download =
        savedTaskRun(
            "fetch",
            TaskType.downloadartifact,
            RunStatus.running,
            RunPhase.running,
            workflowRef,
            uploadTask.getWorkflowRunRef());
    download.setParams(
        new java.util.ArrayList<>(List.of(new RunParam(ArtifactService.NAME_PARAM, "for-download"))));
    TaskRun handedOut = new TaskRun(taskRunRepository.save(download));

    artifactService.fillLinkParams(handedOut);

    assertThat(handedOut.getParams())
        .extracting(RunParam::getName, param -> String.valueOf(param.getValue()))
        .contains(
            org.assertj.core.groups.Tuple.tuple(ArtifactService.SHA256_PARAM, artifact.sha256()),
            org.assertj.core.groups.Tuple.tuple(
                ArtifactService.CONTENT_TYPE_PARAM, "application/octet-stream"));
  }

  @Test
  void aSucceededUploadOverTheLimitEndsFailedWithTheReason() {
    setArtifactSetting(ArtifactService.MAX_ARTIFACT_SIZE, "1");
    TaskRun task = upload("too-big.bin", null, new byte[2 * 1024 * 1024]);
    TaskRunEndRequest succeeded = new TaskRunEndRequest();
    succeeded.setStatus(RunStatus.succeeded);

    taskRunService.end(task.getId(), Optional.of(succeeded));

    TaskRunEntity ended = taskRunRepository.findById(task.getId()).orElseThrow();
    assertThat(ended.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(ended.getStatusReason()).isEqualTo("ArtifactRefused");
    assertThat(ended.getStatusMessage()).contains("too-big.bin").contains("largest artifact");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** A new upload task for the artifact, handed out: its link params are filled. */
  private TaskRun begin(String name, Integer retentionDays) {
    // Each upload is its own task in the run; task names are unique within a run.
    TaskRunEntity task =
        savedTaskRun(
            "upload-" + name + "-" + ++uploadTasks,
            TaskType.uploadartifact,
            RunStatus.running,
            RunPhase.running,
            workflowRef,
            uploadTask.getWorkflowRunRef());
    task.setParams(
        new java.util.ArrayList<>(
            List.of(
                new RunParam(ArtifactService.NAME_PARAM, name),
                new RunParam(
                    ArtifactService.RETENTION_PARAM,
                    retentionDays != null ? retentionDays.toString() : ""))));
    taskRunRepository.save(task);
    TaskRun handedOut = new TaskRun(task);
    artifactService.fillLinkParams(handedOut);
    return handedOut;
  }

  private TaskRun upload(String name, Integer retentionDays, byte[] bytes) {
    TaskRun task = begin(name, retentionDays);
    store.objects.put(keyOf(task), bytes);
    return task;
  }

  private Artifact complete(String name) {
    ArtifactEntity artifact =
        artifactRepository
            .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), name)
            .orElseThrow();
    return artifactService.completeUpload(
        taskRunRepository.findById(artifact.getTaskRunRef()).orElseThrow());
  }

  private static String urlOf(TaskRun task) {
    return task.getParams().stream()
        .filter(param -> ArtifactService.URL_PARAM.equals(param.getName()))
        .map(param -> param.getValue().toString())
        .findFirst()
        .orElseThrow();
  }

  private int retentionOf(String name) {
    return artifactRepository
        .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), name)
        .orElseThrow()
        .getRetentionDays();
  }

  private static String keyOf(TaskRun task) {
    return urlOf(task).substring(InMemoryArtifactStore.PREFIX.length());
  }

  private String createWorkspace(Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    String name = "artifacts-" + UUID.randomUUID().toString().substring(0, 8);
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private void setArtifactSetting(String key, String value) {
    SettingEntity settings = settingsRepository.findOneByKey(ArtifactService.ARTIFACTS_SETTINGS_KEY);
    settings.getConfig().stream()
        .filter(config -> key.equals(config.getKey()))
        .forEach(config -> config.setValue(value));
    settingsRepository.save(settings);
  }

  /** The store as a map, with links that name the key after a fixed prefix. */
  static class InMemoryArtifactStore implements ArtifactStore {

    static final String PREFIX = "memory://artifacts/";

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public Link uploadLink(String key, Duration ttl) {
      return new Link(java.net.URI.create(PREFIX + key), Map.of());
    }

    @Override
    public Link downloadLink(String key, Duration ttl) {
      return new Link(java.net.URI.create(PREFIX + key), Map.of());
    }

    @Override
    public Optional<StoredObject> head(String key) {
      return Optional.ofNullable(objects.get(key))
          .map(bytes -> new StoredObject(bytes.length, "application/octet-stream"));
    }

    @Override
    public InputStream open(String key) {
      return new ByteArrayInputStream(objects.get(key));
    }

    @Override
    public void delete(String key) {
      objects.remove(key);
    }
  }

  @TestConfiguration
  static class InMemoryStoreConfiguration {
    @Bean
    InMemoryArtifactStore inMemoryArtifactStore() {
      return new InMemoryArtifactStore();
    }
  }
}
