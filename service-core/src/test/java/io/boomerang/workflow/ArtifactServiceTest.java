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
import io.boomerang.common.model.ArtifactLink;
import io.boomerang.common.model.ArtifactUploadRequest;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
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
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private WebApplicationContext context;

  private String workspace;
  private String workflowRef;
  private TaskRunEntity uploadTask;

  @BeforeEach
  void seedRun() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);
    // The loader builds this index; auto-index-creation is off, so the test builds it too.
    mongoTemplate
        .indexOps(ArtifactEntity.class)
        .createIndex(
            new Index()
                .on("workflowRunRef", Sort.Direction.ASC)
                .on("name", Sort.Direction.ASC)
                .unique()
                .named("run_name_idx"));

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

    ArtifactLink link = upload("sbom.json", null, bytes);
    Artifact artifact = artifactService.completeUpload(uploadTask.getId(), "sbom.json", null);

    assertThat(link.url()).contains("runs/" + uploadTask.getWorkflowRunRef() + "/sbom.json");
    assertThat(link.sha256()).isNull();
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
    Artifact first = artifactService.completeUpload(uploadTask.getId(), "twice.txt", null);

    assertThat(artifactService.completeUpload(uploadTask.getId(), "twice.txt", null))
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
    ArtifactLink link = upload("big.bin", null, new byte[2 * 1024 * 1024]);

    assertThatThrownBy(() -> artifactService.completeUpload(uploadTask.getId(), "big.bin", null))
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
    artifactService.completeUpload(uploadTask.getId(), "kept.txt", null);
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
    assertThat(begin("short", 3).expiresAt()).isNotNull();
    begin("long", 500);

    assertThat(retentionOf("short")).isEqualTo(3);
    assertThat(retentionOf("long")).isEqualTo(30);
  }

  @Test
  void anExpiredArtifactLosesItsFileButKeepsItsRecord() throws Exception {
    ArtifactLink link = upload("old.log", null, "log".getBytes(StandardCharsets.UTF_8));
    artifactService.completeUpload(uploadTask.getId(), "old.log", null);
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
    ArtifactLink link = upload("stale", null, new byte[] {1});
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
    ArtifactLink link = upload("pruned", null, new byte[] {1});
    artifactService.completeUpload(uploadTask.getId(), "pruned", null);

    artifactService.deleteForWorkflow(workflowRef, 100);

    assertThat(artifactRepository.findByWorkflowRunRefOrderByCreationDateAsc(
            uploadTask.getWorkflowRunRef()))
        .isEmpty();
    assertThat(store.objects).doesNotContainKey(keyOf(link));
  }

  @Test
  void storageCountsAvailableAndUploadingButNotExpired() {
    upload("available", null, new byte[10]);
    artifactService.completeUpload(uploadTask.getId(), "available", null);
    upload("expired", null, new byte[100]);
    artifactService.completeUpload(uploadTask.getId(), "expired", null);
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
    artifactService.completeUpload(uploadTask.getId(), "hello.txt", null);
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

  // ── helpers ───────────────────────────────────────────────────────────────

  private ArtifactLink begin(String name, Integer retentionDays) {
    return artifactService.beginUpload(
        uploadTask.getId(), new ArtifactUploadRequest(name, retentionDays, null));
  }

  private ArtifactLink upload(String name, Integer retentionDays, byte[] bytes) {
    ArtifactLink link = begin(name, retentionDays);
    store.objects.put(keyOf(link), bytes);
    return link;
  }

  private int retentionOf(String name) {
    return artifactRepository
        .findByWorkflowRunRefAndName(uploadTask.getWorkflowRunRef(), name)
        .orElseThrow()
        .getRetentionDays();
  }

  private static String keyOf(ArtifactLink link) {
    return link.url().substring(InMemoryArtifactStore.PREFIX.length());
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
