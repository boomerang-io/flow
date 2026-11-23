package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.engine.DatabaseCommands.Recorded;
import io.boomerang.schedule.ScheduleService;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The relationship-graph reads ({@code rel_nodes}) each hot path costs, recorded on the calling
 * thread: what an embedding product's idle traffic puts on the database with one global token. A
 * health probe and a dispatcher poll tick read no node; a global caller's workspace-scoped read
 * starts at the workspace, not at the root of the whole graph; a scheduled run's submit and its
 * transitions read a fixed handful. A change to these paths updates the counts here.
 */
class RelationshipReadsDatabaseCommandsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "relationship-reads-task";
  private static final String NODES = "find rel_nodes";

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private ScheduleWatcher scheduleWatcher;
  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ScheduleService scheduleService;
  @Autowired private RelationshipNodeRepository nodeRepository;
  @Autowired private RelationshipEdgeRepository edgeRepository;
  @Autowired private MongoTemplate database;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String nonce;
  private String workspace;
  private String workflowRef;
  private String bearer;

  @BeforeEach
  void aWorkspaceWithAScheduledWorkflowAndAGlobalToken() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    nonce = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("rel-reads-" + nonce);
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    Workflow workflow = runnableWorkflow("rel-reads-wf-" + nonce, TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    workflowService.create(workspace, workflow);
    workflowRef =
        relationshipService
            .refsInWorkspace(
                RelationshipType.WORKFLOW, workspace, Optional.of(List.of(workflow.getName())))
            .get(0);

    TokenCreateRequest token = new TokenCreateRequest();
    token.setName("rel-reads-global-" + nonce);
    token.setType(AuthScope.global);
    token.setActorKind(TokenActorKind.SERVICE);
    token.setPermissions(List.of("**/**"));
    token.setExpirationDate(new Date(System.currentTimeMillis() + 3_600_000));
    bearer = "Bearer " + tokenService.create(token).getToken();
    auditCapture(true);
  }

  @AfterEach
  void auditCaptureOff() {
    auditCapture(false);
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aHealthProbeReadsNoRelationshipNode() {
    Recorded<Integer> probe = http(() -> status(get("/health")));
    print("health probe", probe);
    assertThat(nodeReads(probe)).isEmpty();
  }

  /**
   * A global token reaches every node, so its read of one workspace's runs starts at that
   * workspace: the workspace node, then its children. Before, it walked from the root - every
   * user, workspace and global task, then every child of every workspace - in three node reads
   * and as many more batches as the graph is large.
   */
  @Test
  void aGlobalTokensWorkspaceReadStartsAtTheWorkspace() {
    Recorded<Integer> query =
        http(
            () ->
                status(
                    get("/api/v2/workspace/" + workspace + "/workflowrun/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer)));
    print("global token run query", query);
    assertThat(query.result()).isEqualTo(200);
    assertThat(nodeReads(query)).containsExactly(NODES, NODES);
  }

  /**
   * A dispatcher poll resolves its filters once, into the cache every later poll and re-check
   * reads: three task slugs cost one node read, not one each, and the next poll none. The bearer
   * is the global token, through the dispatcher's own authentication.
   */
  @Test
  void aDispatcherPollResolvesItsTaskFilterInOneReadAndThenNone() {
    String dispatcherId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "rel-reads-" + nonce, "rel-reads.local", List.of("template")));
    // No task carries these slugs, so the poll matches nothing and answers at once.
    String filter = "none-a-" + nonce + ",none-b-" + nonce + ",none-c-" + nonce + "*";
    Callable<Integer> poll =
        () ->
            status(
                get("/dispatcher/v1/" + dispatcherId + "/tasks")
                    .param("task", filter)
                    .header(HttpHeaders.AUTHORIZATION, bearer));

    Recorded<Integer> first = http(poll);
    print("dispatcher poll, filter resolved", first);
    assertThat(first.result()).isEqualTo(204);
    assertThat(nodeReads(first)).containsExactly(NODES);

    Recorded<Integer> next = http(poll);
    print("dispatcher poll, filter cached", next);
    assertThat(next.result()).isEqualTo(204);
    assertThat(nodeReads(next)).isEmpty();
  }

  /**
   * A schedule fires off any request, so no per-request memo applies, as the scheduled workflow's
   * key token. Six node reads: the schedule's workflow name, the submit's walk (the workspace node,
   * then its children), the owning workspace's name for the run's quota, the admission's audit
   * event (the workflow's and the workspace's names in one read) and the workspace the run's node
   * hangs from. Before, the walk started at the root and read the whole graph, and the schedule's
   * workflow name and the audit names were read twice: ten reads. The run's completion audits with
   * one read, where it took two.
   */
  @Test
  void aScheduledRunsSubmitAndCompletionReadAFixedHandful() {
    RequestContextHolder.resetRequestAttributes();
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setName("rel-reads-" + nonce);
    schedule.setType(WorkflowScheduleType.cron);
    schedule.setCronSchedule("0 0 * * * ?");
    schedule.setTimezone("UTC");
    schedule.setStatus(WorkflowScheduleStatus.active);
    schedule.setWorkflowRef(workflowRef);
    schedule.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
    String scheduleId = scheduleRepository.save(schedule).getId();
    SecurityContextHolder.clearContext();

    Recorded<Void> fire =
        DatabaseCommands.record(
            () -> {
              scheduleWatcher.fireDueSchedules();
              return null;
            });
    print("schedule fire", fire);
    WorkflowRunEntity run =
        workflowRunRepository.findAll().stream()
            .filter(r -> scheduleId.equals(r.getInitiatedByRef()))
            .findFirst()
            .orElseThrow();
    Recorded<?> cancel = DatabaseCommands.record(() -> workflowRunService.cancel(run.getId()));
    print("run cancel (completion transition)", cancel);
    assertThat(workflowRunRepository.findById(run.getId()).orElseThrow().getPhase())
        .isEqualTo(RunPhase.completed);
    assertThat(nodeReads(fire)).hasSize(6);
    assertThat(nodeReads(cancel)).hasSize(1);
  }

  /**
   * Starting a global caller's walk at the workspaces it names finds exactly what a walk from a
   * member's node finds through the same workspace, for every type that hangs below one.
   */
  @Test
  void aGlobalWalkFromTheWorkspaceFindsWhatAMembersWalkFinds() {
    workflowService.submit(workspace, "rel-reads-wf-" + nonce, manualSubmit(), false);
    UserEntity user = new UserEntity();
    user.setEmail("rel-reads-" + nonce + "@test.local");
    user.setName("Relationship Reads " + nonce);
    user.setType(UserType.user);
    user.setStatus(UserStatus.active);
    String userId = userRepository.save(user).getId();
    relationshipService.createNode(RelationshipType.USER, userId, user.getEmail(), Optional.empty());
    relationshipService.setMemberRole(userId, workspace, "owner");

    for (RelationshipType type :
        List.of(
            RelationshipType.WORKFLOW,
            RelationshipType.WORKFLOWRUN,
            RelationshipType.WORKSPACETASK,
            RelationshipType.APPROVERGROUP,
            RelationshipType.INTEGRATION,
            RelationshipType.WORKSPACE)) {
      List<String> global =
          relationshipService.findNodeRefs(RelationshipType.ROOT, "root", type).stream().toList();
      List<String> viaWorkspace =
          relationshipService
              .findNodes(
                  RelationshipType.ROOT,
                  "root",
                  type,
                  Optional.empty(),
                  Optional.of(RelationshipType.WORKSPACE),
                  Optional.of(List.of(workspace)))
              .stream()
              .map(n -> n.getRef())
              .toList();
      List<String> member =
          relationshipService
              .findNodes(
                  RelationshipType.USER,
                  userId,
                  type,
                  Optional.empty(),
                  Optional.of(RelationshipType.WORKSPACE),
                  Optional.of(List.of(workspace)))
              .stream()
              .map(n -> n.getRef())
              .toList();
      assertThat(viaWorkspace).as(type.getLabel()).containsExactlyInAnyOrderElementsOf(member);
      assertThat(global).as(type.getLabel()).containsAll(viaWorkspace);
    }
  }

  /**
   * A member's lookups read the same documents whatever the number of runs in the workspace: a
   * workflow lookup, a run check, a schedule query and get, and a global task lookup read no run
   * edge and no run node. Before, each read every run's edge and node, and the workflow and
   * schedule paths sent a third query naming every run.
   */
  @Test
  void aMembersLookupsReadTheSameDocumentsAtTenAndAThousandRuns() {
    String member = memberOfThreeWorkspaces();
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setName("rel-reads-runs-" + nonce);
    schedule.setType(WorkflowScheduleType.cron);
    schedule.setCronSchedule("0 0 * * * ?");
    schedule.setTimezone("UTC");
    schedule.setStatus(WorkflowScheduleStatus.active);
    schedule.setWorkflowRef(workflowRef);
    String scheduleId = scheduleRepository.save(schedule).getId();
    String run = seedRuns(10).get(0);

    Map<String, Recorded<?>> atTen = memberLookups(member, run, scheduleId);
    seedRuns(990);
    Map<String, Recorded<?>> atAThousand = memberLookups(member, run, scheduleId);

    atTen.forEach(
        (path, recorded) -> {
          Recorded<?> large = atAThousand.get(path);
          System.out.printf(
              "REL DOCS | %-24s | 10 runs: %d edges %d nodes | 1000 runs: %d edges %d nodes%n",
              path,
              recorded.documentsRead("rel_edges"),
              recorded.documentsRead("rel_nodes"),
              large.documentsRead("rel_edges"),
              large.documentsRead("rel_nodes"));
        });
    atTen.forEach(
        (path, recorded) -> {
          Recorded<?> large = atAThousand.get(path);
          assertThat(large.documentsRead("rel_edges"))
              .as(path)
              .isEqualTo(recorded.documentsRead("rel_edges"));
          assertThat(large.documentsRead("rel_nodes"))
              .as(path)
              .isEqualTo(recorded.documentsRead("rel_nodes"));
          assertThat(large.names()).as(path).isEqualTo(recorded.names());
        });
    assertThat(atAThousand.get("run check").result()).isEqualTo(true);
    assertThat(atAThousand.get("workflow lookup").result()).isEqualTo(List.of(workflowRef));
  }

  /**
   * A member's Workflows page, served over HTTP with their session: authentication reads the
   * member's memberships and every lookup after it in the request uses that one read. The page
   * finds the workspace's workflows in one walk and reads no workflow whole to list the templates
   * beside them.
   */
  @Test
  void aMembersWorkflowsPageReadsTheirMembershipsOnceAndWalksTheWorkspaceOnce() {
    String member = memberOfThreeWorkspaces();
    String email = userRepository.findById(member).orElseThrow().getEmail();
    String session =
        "Bearer " + tokenService.createSessionTokenWithRaw(email, "Rel", "Reads", false, false).rawToken();

    Recorded<Integer> page =
        http(
            () ->
                status(
                    get("/api/v2/workspace/" + workspace + "/workflow/query")
                        .header(HttpHeaders.AUTHORIZATION, session)));
    List<String> names = page.names().stream().map(name -> name.replace(" flowtest_", " ")).toList();
    System.out.printf(
        "REL DOCS | member's workflows page | %d edges %d nodes | %s%n",
        page.documentsRead("rel_edges"), page.documentsRead("rel_nodes"), names);
    assertThat(page.result()).isEqualTo(200);
    // The member's three memberships once, the workspace's one workflow edge for the walk, and
    // the same edge as the workspace's own.
    assertThat(page.documentsRead("rel_edges")).isEqualTo(3 + 1 + 1);
    assertThat(names.stream().filter(name -> name.equals("find rel_edges"))).hasSize(3);
    // No workflow is read by id to find the templates the workspace already links.
    assertThat(names.stream().filter(name -> name.equals("find workflows")).count())
        .isLessThanOrEqualTo(3);

    // A request that names workflows asks the relationship layer for those names as well, since
    // a workflow's own token reaches its workflow only by name: one more walk of the workspace's
    // workflow edges, still on the one read of the memberships.
    Recorded<Integer> named =
        http(
            () ->
                status(
                    get("/api/v2/workspace/" + workspace + "/workflow/query")
                        .param("workflows", "rel-reads-wf-" + nonce)
                        .header(HttpHeaders.AUTHORIZATION, session)));
    List<String> namedNames =
        named.names().stream().map(name -> name.replace(" flowtest_", " ")).toList();
    System.out.printf(
        "REL DOCS | member's workflows page, named | %d edges %d nodes | %s%n",
        named.documentsRead("rel_edges"), named.documentsRead("rel_nodes"), namedNames);
    assertThat(named.result()).isEqualTo(200);
    assertThat(named.documentsRead("rel_edges")).isEqualTo(3 + 1 + 1 + 1);
    assertThat(namedNames.stream().filter(name -> name.equals("find rel_edges"))).hasSize(4);
  }

  /**
   * A walk level for anything but a run asks for its edges by the labels that lead to its target,
   * which the from_label index answers without reading a run edge.
   */
  @Test
  void aWalkLevelLeavesTheRunEdgesUnreadOnTheFromLabelIndex() {
    seedRuns(200);
    String workspaceNode =
        relationshipService
            .nodesBySlug(RelationshipType.WORKSPACE, List.of(workspace))
            .get(workspace)
            .getId();
    Document explain =
        database
            .getDb()
            .runCommand(
                new Document(
                        "explain",
                        new Document(
                                "find", database.getCollectionName(RelationshipEdgeEntity.class))
                            .append(
                                "filter",
                                new Document("from", new Document("$in", List.of(workspaceNode)))
                                    .append(
                                        "label",
                                        new Document(
                                            "$in",
                                            List.of(
                                                RelationshipLabel.HAS_WORKFLOW.getLabel(),
                                                RelationshipLabel.LINKS_TEMPLATE.getLabel())))))
                    .append("verbosity", "executionStats"));
    Document stats = explain.get("executionStats", Document.class);
    System.out.printf(
        "REL EXPLAIN | keys %s docs %s returned %s%n",
        stats.get("totalKeysExamined"), stats.get("totalDocsExamined"), stats.get("nReturned"));
    assertThat(explain.get("queryPlanner", Document.class).toJson()).contains("\"from_label\"");
    assertThat(stats.get("totalDocsExamined")).isEqualTo(stats.get("nReturned"));
    assertThat(((Number) stats.get("totalKeysExamined")).intValue()).isLessThan(20);
  }

  // Each lookup in a request of its own, as the member: what each costs on the database.
  private Map<String, Recorded<?>> memberLookups(String member, String run, String scheduleId) {
    Map<String, Recorded<?>> recorded = new LinkedHashMap<>();
    recorded.put(
        "workflow lookup",
        asMember(
            member,
            () ->
                relationshipService.refsInWorkspace(
                    RelationshipType.WORKFLOW,
                    workspace,
                    Optional.of(List.of("rel-reads-wf-" + nonce)))));
    recorded.put(
        "run check",
        asMember(
            member,
            () ->
                relationshipService.check(
                    RelationshipType.WORKFLOWRUN,
                    run,
                    Optional.of(RelationshipType.WORKSPACE),
                    Optional.of(List.of(workspace)))));
    recorded.put(
        "schedule query",
        asMember(
            member,
            () ->
                scheduleService
                    .query(
                        workspace,
                        Optional.of(0),
                        Optional.of(10),
                        Sort.by("creationDate"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())
                    .getTotalElements()));
    recorded.put(
        "schedule get",
        asMember(member, () -> scheduleService.get(workspace, scheduleId).getId()));
    recorded.put(
        "global task lookup",
        asMember(
            member,
            () ->
                relationshipService.filter(
                    RelationshipType.TASK,
                    Optional.of(List.of(TASK_SLUG)),
                    Optional.empty(),
                    Optional.empty())));
    return recorded;
  }

  private <T> Recorded<T> asMember(String member, Supplier<T> lookup) {
    Token token = new Token(AuthScope.session);
    token.setPrincipal(member);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, workspace, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(member, null);
    authentication.setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      return DatabaseCommands.record(lookup);
    } finally {
      RequestContextHolder.resetRequestAttributes();
      SecurityContextHolder.clearContext();
    }
  }

  // A user who belongs to the workspace and two others, so a walk from them could fan out.
  private String memberOfThreeWorkspaces() {
    UserEntity user = new UserEntity();
    user.setEmail("rel-reads-member-" + nonce + "@test.local");
    user.setName("Relationship Reads Member " + nonce);
    user.setType(UserType.user);
    user.setStatus(UserStatus.active);
    String userId = userRepository.save(user).getId();
    relationshipService.createNode(
        RelationshipType.USER, userId, user.getEmail(), Optional.empty());
    relationshipService.setMemberRole(userId, workspace, "owner");
    for (String other : List.of("a", "b")) {
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName("rel-reads-" + other + "-" + nonce);
      request.setDisplayName(request.getName());
      relationshipService.setMemberRole(
          userId, workspaceService.create(request).getName(), "owner");
    }
    return userId;
  }

  // Runs under the workspace, as their relationship nodes and ownership edges; their ids.
  private List<String> seedRuns(int count) {
    String workspaceNode =
        relationshipService
            .nodesBySlug(RelationshipType.WORKSPACE, List.of(workspace))
            .get(workspace)
            .getId();
    List<String> ids = new ArrayList<>();
    List<RelationshipNodeEntity> nodes = new ArrayList<>();
    List<RelationshipEdgeEntity> edges = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String id = new ObjectId().toHexString();
      ids.add(id);
      nodes.add(
          new RelationshipNodeEntity(
              RelationshipType.WORKFLOWRUN.getLabel(), id, id, Optional.empty()));
      edges.add(
          new RelationshipEdgeEntity(
              workspaceNode,
              RelationshipLabel.HAS_WORKFLOWRUN,
              RelationshipType.WORKFLOWRUN.getLabel() + ":" + id,
              Optional.empty()));
    }
    nodeRepository.saveAll(nodes);
    edgeRepository.saveAll(edges);
    return ids;
  }

  private static io.boomerang.common.model.WorkflowSubmitRequest manualSubmit() {
    io.boomerang.common.model.WorkflowSubmitRequest request =
        new io.boomerang.common.model.WorkflowSubmitRequest();
    request.setTrigger(io.boomerang.common.enums.TriggerEnum.manual);
    return request;
  }

  // A served request: no identity but the bearer's, and the request scope the filter chain opens.
  private Recorded<Integer> http(Callable<Integer> request) {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
    try {
      return DatabaseCommands.record(
          () -> {
            try {
              return request.call();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private int status(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private static List<String> nodeReads(Recorded<?> recorded) {
    return recorded.names().stream()
        .map(name -> name.replace(" flowtest_", " "))
        .filter(name -> name.endsWith(" rel_nodes") && !name.startsWith("update"))
        .toList();
  }

  private static void print(String operation, Recorded<?> recorded) {
    System.out.printf(
        "REL NODES | %-36s | %d node reads | %s%n",
        operation,
        nodeReads(recorded).size(),
        recorded.names().stream().map(name -> name.replace(" flowtest_", " ")).toList());
  }

  private void auditCapture(boolean on) {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (!on) {
      if (settings != null) {
        settingsRepository.delete(settings);
      }
      return;
    }
    if (settings == null) {
      settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
