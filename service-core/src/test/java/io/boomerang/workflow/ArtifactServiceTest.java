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
import io.boomerang.common.enums.ArtifactScope;
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
import io.boomerang.workflow.ArtifactService.LinkOutcome;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
 * expired artifact keeps its record and loses its file; nothing is deleted to make room. A
 * workflow-scoped artifact is kept across runs under its own key, replaced by the next verified
 * upload of its name, and never expires on its own.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
public class ArtifactServiceTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "artifact-test-task";
  private static final String WORKFLOW_NAME = "artifact-workflow";
  private static final String QUOTA_FEATURE = "workspaceQuotas";

  @Autowired private ArtifactService artifactService;
  @Autowired private QuotaService quotaService;
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
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);

    workspace = createWorkspace(new Quotas());
    workflowService.create(workspace, runnableWorkflow(WORKFLOW_NAME, TASK_SLUG));
    WorkflowRun run = submitRun();
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
    setArtifactSetting(ArtifactService.LOGS_ENABLED, "true");
    setArtifactSetting(ArtifactService.LOG_RETENTION_DAYS, "7");
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
    // A record holding a whole Gi fills the 1Gi quota without storing a Gi in the test.
    ArtifactEntity full = new ArtifactEntity();
    full.setName("full.bin");
    full.setWorkflowRef(workflowRef);
    full.setWorkflowRunRef("some-other-run");
    full.setScope(ArtifactScope.run);
    full.setStatus(ArtifactStatus.available);
    full.setSize(1024L * 1024 * 1024);
    artifactRepository.save(full);
    setArtifactQuota(1);
    setFeatureSetting(QUOTA_FEATURE, true);

    assertThatThrownBy(() -> begin("refused.txt", null))
        .isInstanceOfSatisfying(
            BoomerangException.class, ex -> assertThat(ex.getReason()).isEqualTo("QUOTA_EXCEEDED"));
    assertThat(artifactService.listForRun(workspace, uploadTask.getWorkflowRunRef()))
        .extracting(Artifact::name)
        .containsExactly("kept.txt");
  }

  @Test
  void anArtifactStorageQuotaOfZeroIsUnlimited() {
    setArtifactQuota(0);
    setFeatureSetting(QUOTA_FEATURE, true);

    upload("unlimited.txt", null, new byte[] {7});

    assertThat(complete("unlimited.txt").status()).isEqualTo(ArtifactStatus.available);
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

    assertThat(quotaService.current(workspace).getCurrentArtifactStorage()).isEqualTo(10);
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

  // ── workflow scope ────────────────────────────────────────────────────────

  @Test
  void aWorkflowScopedArtifactIsKeptUnderItsOwnKeyWithoutExpiry() {
    TaskRun link = beginIn(uploadTask.getWorkflowRunRef(), "model.bin", null, "workflow");
    store.objects.put(keyOf(link), new byte[] {1, 2});
    Artifact artifact = complete(uploadTask.getWorkflowRunRef(), "model.bin");

    assertThat(keyOf(link))
        .isEqualTo("workflows/" + workflowRef + "/model.bin/" + artifact.id());
    assertThat(artifact.scope()).isEqualTo(ArtifactScope.workflow);
    assertThat(artifact.status()).isEqualTo(ArtifactStatus.available);
    assertThat(artifact.retentionDays()).isZero();
    assertThat(artifact.expirationDate()).isNull();
  }

  @Test
  void aLaterRunOfTheWorkflowDownloadsTheKeptArtifact() {
    byte[] bytes = "kept state".getBytes(StandardCharsets.UTF_8);
    uploadIn(uploadTask.getWorkflowRunRef(), "state.json", "workflow", bytes);
    Artifact kept = complete(uploadTask.getWorkflowRunRef(), "state.json");
    String laterRun = submitRun().getId();

    TaskRun download = download(laterRun, "state.json", "workflow", null);

    assertThat(artifactService.fillLinkParams(download)).isEqualTo(LinkOutcome.FILLED);
    assertThat(paramOf(download, ArtifactService.SHA256_PARAM)).isEqualTo(kept.sha256());
    assertThat(keyOf(download)).isEqualTo(keyOfArtifact(kept));
  }

  @Test
  void aRunScopedDownloadDoesNotSeeTheWorkflowsKeptArtifact() {
    uploadIn(uploadTask.getWorkflowRunRef(), "only-workflow", "workflow", new byte[] {1});
    complete(uploadTask.getWorkflowRunRef(), "only-workflow");
    TaskRun download = download(submitRun().getId(), "only-workflow", null, null);

    assertThatThrownBy(() -> artifactService.fillLinkParams(download))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_REF"));
  }

  @Test
  void aVerifiedReplacementExpiresThePreviousVersionAndKeepsItsRecord() {
    String firstRun = uploadTask.getWorkflowRunRef();
    String secondRun = submitRun().getId();
    TaskRun firstLink = uploadIn(firstRun, "latest.tgz", "workflow", new byte[] {1});
    Artifact first = complete(firstRun, "latest.tgz");
    TaskRun secondLink = uploadIn(secondRun, "latest.tgz", "workflow", new byte[] {2, 2});

    // Until the second upload is verified, the first version is still the one handed out.
    assertThat(keyOf(handedOutDownload(submitRun().getId(), "latest.tgz", "workflow")))
        .isEqualTo(keyOf(firstLink));
    Artifact second = complete(secondRun, "latest.tgz");

    assertThat(store.objects).doesNotContainKey(keyOf(firstLink)).containsKey(keyOf(secondLink));
    assertThat(artifactRepository.findById(first.id()).orElseThrow().getStatus())
        .isEqualTo(ArtifactStatus.expired);
    assertThat(availableWorkflowScoped("latest.tgz")).extracting(ArtifactEntity::getId).containsExactly(second.id());
    assertThat(keyOf(handedOutDownload(submitRun().getId(), "latest.tgz", "workflow")))
        .isEqualTo(keyOf(secondLink));
    assertThatThrownBy(() -> artifactService.open(workspace, firstRun, "latest.tgz"))
        .isInstanceOfSatisfying(
            BoomerangException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(410));
  }

  @Test
  void theLaterVerifiedCompletionWinsWhicheverUploadBeganFirst() {
    String firstRun = uploadTask.getWorkflowRunRef();
    String secondRun = submitRun().getId();
    uploadIn(firstRun, "race", "workflow", new byte[] {1});
    uploadIn(secondRun, "race", "workflow", new byte[] {2});

    Artifact second = complete(secondRun, "race");
    Artifact first = complete(firstRun, "race");

    assertThat(availableWorkflowScoped("race")).extracting(ArtifactEntity::getId).containsExactly(first.id());
    assertThat(artifactRepository.findById(second.id()).orElseThrow().getStatus())
        .isEqualTo(ArtifactStatus.expired);
    assertThat(artifactRepository.findByWorkflowRef(workflowRef, PageRequest.of(0, 10)))
        .filteredOn(artifact -> "race".equals(artifact.getName()))
        .hasSize(2);
  }

  @Test
  void aRefusedReplacementLeavesTheKeptArtifactInPlace() {
    String firstRun = uploadTask.getWorkflowRunRef();
    TaskRun firstLink = uploadIn(firstRun, "kept.bin", "workflow", new byte[] {1});
    Artifact first = complete(firstRun, "kept.bin");
    setArtifactSetting(ArtifactService.MAX_ARTIFACT_SIZE, "1");
    String secondRun = submitRun().getId();
    TaskRun secondLink = uploadIn(secondRun, "kept.bin", "workflow", new byte[2 * 1024 * 1024]);

    assertThatThrownBy(() -> complete(secondRun, "kept.bin"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_TOO_LARGE"));

    assertThat(store.objects).containsKey(keyOf(firstLink)).doesNotContainKey(keyOf(secondLink));
    assertThat(availableWorkflowScoped("kept.bin")).extracting(ArtifactEntity::getId).containsExactly(first.id());
  }

  @Test
  void retentionDaysIsRefusedWithScopeWorkflow() {
    assertThatThrownBy(() -> beginIn(uploadTask.getWorkflowRunRef(), "no-retention", 3, "workflow"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_SCOPE");
              assertThat(artifactService.refusalMessage(ex))
                  .contains("retention-days cannot be set with scope workflow");
            });
    assertThat(
            artifactRepository.findByWorkflowRunRefAndName(
                uploadTask.getWorkflowRunRef(), "no-retention"))
        .isEmpty();
  }

  @Test
  void anUnknownScopeIsRefusedOnUploadAndDownload() {
    assertThatThrownBy(() -> beginIn(uploadTask.getWorkflowRunRef(), "bad-scope", null, "team"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_SCOPE"));
    TaskRun download = download(uploadTask.getWorkflowRunRef(), "bad-scope", "global", null);
    assertThatThrownBy(() -> artifactService.fillLinkParams(download))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_SCOPE"));
  }

  @Test
  void anOptionalDownloadWithNothingToFetchIsNotHandedOut() {
    for (String scope : new String[] {null, "workflow"}) {
      TaskRun optional = download(uploadTask.getWorkflowRunRef(), "absent", scope, true);
      assertThat(artifactService.fillLinkParams(optional)).as(scope).isEqualTo(LinkOutcome.NOT_FOUND);
      assertThat(optional.getParams()).extracting(RunParam::getName).doesNotContain("url");

      TaskRun required = download(uploadTask.getWorkflowRunRef(), "absent", scope, false);
      assertThatThrownBy(() -> artifactService.fillLinkParams(required))
          .as(scope)
          .isInstanceOfSatisfying(
              BoomerangException.class,
              ex -> assertThat(ex.getReason()).isEqualTo("ARTIFACT_INVALID_REF"));
    }
  }

  @Test
  void theWorkspaceQueryFiltersByScopeAndWorkflow() {
    upload("per-run", null, new byte[] {1});
    complete("per-run");
    uploadIn(uploadTask.getWorkflowRunRef(), "per-workflow", "workflow", new byte[] {1, 2});
    complete(uploadTask.getWorkflowRunRef(), "per-workflow");

    assertThat(query(Optional.empty(), Optional.empty()))
        .containsExactlyInAnyOrder("per-run", "per-workflow");
    assertThat(query(Optional.of(ArtifactScope.workflow), Optional.empty()))
        .containsExactly("per-workflow");
    assertThat(query(Optional.of(ArtifactScope.run), Optional.empty())).containsExactly("per-run");
    assertThat(query(Optional.empty(), Optional.of(WORKFLOW_NAME)))
        .containsExactlyInAnyOrder("per-run", "per-workflow");
    assertThat(query(Optional.of(ArtifactScope.workflow), Optional.of(WORKFLOW_NAME)))
        .containsExactly("per-workflow");
    assertThat(query(Optional.empty(), Optional.of("no-such-workflow"))).isEmpty();
  }

  @Test
  void theRunRouteServesAWorkflowScopedArtifactItsRunUploaded() throws Exception {
    byte[] bytes = "kept file".getBytes(StandardCharsets.UTF_8);
    uploadIn(uploadTask.getWorkflowRunRef(), "kept.txt", "workflow", bytes);
    complete(uploadTask.getWorkflowRunRef(), "kept.txt");
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

    MvcResult started =
        mockMvc
            .perform(
                get(
                    "/api/v2/workspace/{workspace}/workflowrun/{run}/artifacts/{name}",
                    workspace,
                    uploadTask.getWorkflowRunRef(),
                    "kept.txt"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes));
    assertThat(artifactService.listForRun(workspace, uploadTask.getWorkflowRunRef()))
        .extracting(Artifact::name, Artifact::scope)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("kept.txt", ArtifactScope.workflow));
  }

  @Test
  void aStoredTaskLogIsNeitherListedNorCountedNorServedAsAnArtifact() {
    String run = uploadTask.getWorkflowRunRef();
    artifactService.storeTaskLog(uploadTask, "connected\n".getBytes(StandardCharsets.UTF_8));

    ArtifactEntity log = artifactService.taskLog(uploadTask).orElseThrow();
    assertThat(log.getScope()).isEqualTo(ArtifactScope.log);
    assertThat(log.getTaskRunRef()).isEqualTo(uploadTask.getId());
    assertThat(log.getExpirationDate()).isAfter(new Date());
    assertThat(store.objects).containsKey(log.getStorageKey());

    assertThat(artifactService.listForRun(workspace, run)).isEmpty();
    assertThat(quotaService.current(workspace).getCurrentArtifactStorage()).isZero();
    assertThat(
            artifactService
                .query(workspace, Optional.empty(), Optional.empty(), Optional.empty(), 0, 10)
                .getTotalElements())
        .isZero();
    assertThat(
            artifactService
                .query(workspace, Optional.empty(), Optional.of(ArtifactScope.run), Optional.empty(), 0, 10)
                .getTotalElements())
        .isZero();
    assertThat(
            artifactService
                .query(workspace, Optional.empty(), Optional.of(ArtifactScope.log), Optional.empty(), 0, 10)
                .getTotalElements())
        .isEqualTo(1);
    assertThatThrownBy(() -> artifactService.open(workspace, run, log.getName()))
        .isInstanceOf(BoomerangException.class);

    // A requeued attempt's upload replaces the first, file and record.
    artifactService.storeTaskLog(uploadTask, "retried\n".getBytes(StandardCharsets.UTF_8));
    assertThat(artifactRepository.findByWorkflowRunRefAndName(run, log.getName()))
        .get()
        .extracting(ArtifactEntity::getSize)
        .isEqualTo(8L);
    assertThat(store.objects.get(log.getStorageKey()))
        .isEqualTo("retried\n".getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A periodic upload and the final one can overlap: both read no record and both insert. The one
   * refused by the unique index takes over the other's record instead of failing, so the task's
   * log is stored either way and one record is available.
   */
  @Test
  void twoOverlappingUploadsOfATaskLogKeepOneRecord() throws Exception {
    String run = uploadTask.getWorkflowRunRef();
    String name = ArtifactService.logName(uploadTask.getId());
    CountDownLatch bothRead = new CountDownLatch(2);
    ExecutorService uploads = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> stores = new ArrayList<>();
      for (String text : List.of("periodic\n", "periodic\nfinal\n")) {
        stores.add(
            uploads.submit(
                () -> {
                  // Both uploads look for the record before either saves one.
                  assertThat(artifactRepository.findByWorkflowRunRefAndName(run, name)).isEmpty();
                  bothRead.countDown();
                  assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
                  artifactService.storeTaskLog(uploadTask, text.getBytes(StandardCharsets.UTF_8));
                  return null;
                }));
      }
      for (Future<?> store : stores) {
        store.get(10, TimeUnit.SECONDS);
      }
    } finally {
      uploads.shutdownNow();
    }

    assertThat(artifactRepository.findAll())
        .filteredOn(a -> run.equals(a.getWorkflowRunRef()) && name.equals(a.getName()))
        .hasSize(1);
    ArtifactEntity log = artifactService.taskLog(uploadTask).orElseThrow();
    assertThat(store.objects).containsKey(log.getStorageKey());
    assertThat(log.getSize()).isIn(9L, 15L);
  }

  @Test
  void taskLogsKeepTheirOwnRetentionAndCanBeSwitchedOff() {
    setArtifactSetting(ArtifactService.LOG_RETENTION_DAYS, "2");
    artifactService.storeTaskLog(uploadTask, "kept two days\n".getBytes(StandardCharsets.UTF_8));
    ArtifactEntity log = artifactService.taskLog(uploadTask).orElseThrow();
    assertThat(log.getRetentionDays()).isEqualTo(2);
    long days =
        TimeUnit.MILLISECONDS.toDays(
            log.getExpirationDate().getTime() - log.getCreationDate().getTime());
    assertThat(days).isEqualTo(2);

    setArtifactSetting(ArtifactService.LOGS_ENABLED, "false");
    assertThatThrownBy(
            () -> artifactService.storeTaskLog(uploadTask, "refused\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("TASKRUN_LOGS_DISABLED"));
    // What was stored before the switch is still served until it expires.
    assertThat(artifactService.taskLog(uploadTask)).isPresent();
  }

  @Test
  void anUploadTaskMayNotClaimTheLogScope() {
    uploadTask.setParams(
        List.of(new RunParam(ArtifactService.NAME_PARAM, "x.log"), new RunParam(ArtifactService.SCOPE_PARAM, "log")));

    assertThatThrownBy(() -> artifactService.fillLinkParams(taskRunModelOf(uploadTask)))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("ARTIFACT_INVALID_SCOPE"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static TaskRun taskRunModelOf(TaskRunEntity entity) {
    TaskRun task = new TaskRun();
    task.setId(entity.getId());
    task.setType(TaskType.uploadartifact);
    task.setWorkflowRef(entity.getWorkflowRef());
    task.setWorkflowRunRef(entity.getWorkflowRunRef());
    task.setParams(entity.getParams());
    return task;
  }

  private WorkflowRun submitRun() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return workflowService.submit(workspace, WORKFLOW_NAME, request, false);
  }

  /** A new upload task for the artifact, handed out: its link params are filled. */
  private TaskRun begin(String name, Integer retentionDays) {
    return beginIn(uploadTask.getWorkflowRunRef(), name, retentionDays, null);
  }

  private TaskRun beginIn(String runId, String name, Integer retentionDays, String scope) {
    // Each upload is its own task in the run; task names are unique within a run.
    TaskRunEntity task =
        savedTaskRun(
            "upload-" + name + "-" + ++uploadTasks,
            TaskType.uploadartifact,
            RunStatus.running,
            RunPhase.running,
            workflowRef,
            runId);
    task.setParams(
        new java.util.ArrayList<>(
            List.of(
                new RunParam(ArtifactService.NAME_PARAM, name),
                new RunParam(ArtifactService.SCOPE_PARAM, scope != null ? scope : ""),
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

  private TaskRun uploadIn(String runId, String name, String scope, byte[] bytes) {
    TaskRun task = beginIn(runId, name, null, scope);
    store.objects.put(keyOf(task), bytes);
    return task;
  }

  private Artifact complete(String name) {
    return complete(uploadTask.getWorkflowRunRef(), name);
  }

  private Artifact complete(String runId, String name) {
    ArtifactEntity artifact =
        artifactRepository.findByWorkflowRunRefAndName(runId, name).orElseThrow();
    return artifactService.completeUpload(
        taskRunRepository.findById(artifact.getTaskRunRef()).orElseThrow());
  }

  /** A download task in the run, saved but not yet handed out. */
  private TaskRun download(String runId, String name, String scope, Boolean optional) {
    TaskRunEntity task =
        savedTaskRun(
            "download-" + name + "-" + ++uploadTasks,
            TaskType.downloadartifact,
            RunStatus.running,
            RunPhase.running,
            workflowRef,
            runId);
    List<RunParam> params = new java.util.ArrayList<>();
    params.add(new RunParam(ArtifactService.NAME_PARAM, name));
    if (scope != null) {
      params.add(new RunParam(ArtifactService.SCOPE_PARAM, scope));
    }
    if (optional != null) {
      params.add(new RunParam(ArtifactService.OPTIONAL_PARAM, optional.toString()));
    }
    task.setParams(params);
    return new TaskRun(taskRunRepository.save(task));
  }

  /** A download task handed out: its link params are filled. */
  private TaskRun handedOutDownload(String runId, String name, String scope) {
    TaskRun task = download(runId, name, scope, null);
    artifactService.fillLinkParams(task);
    return task;
  }

  private List<ArtifactEntity> availableWorkflowScoped(String name) {
    return artifactRepository
        .findByWorkflowRefAndNameAndScopeAndStatus(
            workflowRef, name, ArtifactScope.workflow, ArtifactStatus.available)
        .stream()
        .toList();
  }

  private List<String> query(Optional<ArtifactScope> scope, Optional<String> workflow) {
    return artifactService
        .query(workspace, Optional.empty(), scope, workflow, 0, 10)
        .map(Artifact::name)
        .getContent();
  }

  private static String paramOf(TaskRun task, String name) {
    return task.getParams().stream()
        .filter(param -> name.equals(param.getName()))
        .map(param -> param.getValue().toString())
        .findFirst()
        .orElseThrow();
  }

  private String keyOfArtifact(Artifact artifact) {
    return artifactRepository.findById(artifact.id()).orElseThrow().getStorageKey();
  }

  private static String urlOf(TaskRun task) {
    return paramOf(task, ArtifactService.URL_PARAM);
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

  private void setArtifactQuota(int gi) {
    Quotas quotas = new Quotas();
    quotas.setMaxArtifactStorage(gi);
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(quotas);
    workspaceService.patch(workspace, patch);
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
    public void put(String key, byte[] content, String contentType) {
      objects.put(key, content);
    }

    @Override
    public void delete(String key) {
      objects.remove(key);
    }
  }

  /** Shared with the engine's log storage test, which stores logs through the same store. */
  @TestConfiguration
  public static class InMemoryStoreConfiguration {
    @Bean
    InMemoryArtifactStore inMemoryArtifactStore() {
      return new InMemoryArtifactStore();
    }
  }
}
