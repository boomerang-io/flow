package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.ArtifactRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A dispatcher's poll and end calls carry no user identity - with dispatcher auth off nothing
 * authenticates them, and with it on the machine token is checked but never becomes the caller.
 * With workspace quotas enforced, an upload task is still handed out with its link filled and
 * its upload still completes: the artifact quota is read from the run's workspace, not through
 * the caller.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class ArtifactHandOutWithoutIdentityTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "handout-test-task";
  private static final String QUOTA_FEATURE = "workspaceQuotas";

  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactServiceTest.InMemoryArtifactStore store;
  @Autowired private TokenService tokenService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private String label;
  private TaskRunEntity upload;
  private String agent;

  @BeforeEach
  void claimableUploadInAWorkspaceRun() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting(QUOTA_FEATURE, true);

    WorkspaceRequest request = new WorkspaceRequest();
    String workspace = "handout-" + tag;
    request.setName(workspace);
    request.setDisplayName(workspace);
    request.setQuotas(new Quotas());
    workspaceService.create(request);

    label = "handout-test=" + tag;
    Workflow workflow = runnableWorkflow("handout-" + tag, TASK_SLUG);
    workflow.getLabels().put("handout-test", tag);
    workflowService.create(workspace, workflow);
    WorkflowSubmitRequest submit = new WorkflowSubmitRequest();
    submit.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, workflow.getName(), submit, false);

    upload =
        savedTaskRun(
            "upload-report",
            TaskType.uploadartifact,
            RunStatus.ready,
            RunPhase.pending,
            run.getWorkflowRef(),
            run.getId());
    upload.setParams(
        new ArrayList<>(List.of(new RunParam(ArtifactService.NAME_PARAM, "report.json"))));
    upload = taskRunRepository.save(upload);

    agent =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "handout-" + tag, "handout-" + tag + ".local", List.of("uploadartifact")));
  }

  @AfterEach
  void restoreQuotaFeature() {
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void aPollWithNoIdentityHandsOutTheUploadWithItsLinkAndTheUploadCompletes() {
    SecurityContextHolder.clearContext();

    ResponseEntity<List<TaskRun>> polled =
        dispatcherService.getTaskQueue(agent, 1, null, null, label);

    assertThat(polled.getBody()).extracting(TaskRun::getId).containsExactly(upload.getId());
    TaskRun handedOut = polled.getBody().get(0);
    String url = param(handedOut, ArtifactService.URL_PARAM);
    assertThat(url).startsWith(ArtifactServiceTest.InMemoryArtifactStore.PREFIX);

    store.objects.put(
        url.substring(ArtifactServiceTest.InMemoryArtifactStore.PREFIX.length()),
        "{}".getBytes(StandardCharsets.UTF_8));
    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setDispatcherRef(agent);
    taskRunService.end(upload.getId(), Optional.of(end));

    assertThat(taskRunRepository.findById(upload.getId()).orElseThrow().getStatus())
        .isEqualTo(RunStatus.succeeded);
    assertThat(
            artifactRepository
                .findByWorkflowRunRefAndName(upload.getWorkflowRunRef(), "report.json")
                .orElseThrow()
                .getStatus())
        .isEqualTo(ArtifactStatus.available);
  }

  @Test
  void aPollWithAMachineTokenThroughTheSecurityChainHandsOutTheUpload() throws Exception {
    String token = mintMachineToken();
    SecurityContextHolder.clearContext();
    MockMvc mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();

    mockMvc
        .perform(
            get("/dispatcher/v1/" + agent + "/tasks")
                .param("limit", "1")
                .param("workflowLabel", label)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(upload.getId())))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        ArtifactServiceTest.InMemoryArtifactStore.PREFIX)));
  }

  private String mintMachineToken() {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("handout-test-admin");
    admin.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    authentication.setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("handout-test-dispatcher");
    request.setActorKind(TokenActorKind.SERVICE);
    request.setPermissions(List.of("**/**"));
    return tokenService.create(request).getToken();
  }

  private static String param(TaskRun task, String name) {
    return task.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .map(p -> String.valueOf(p.getValue()))
        .findFirst()
        .orElse(null);
  }
}
