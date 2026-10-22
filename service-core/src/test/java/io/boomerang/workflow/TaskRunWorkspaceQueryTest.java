package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunResponsePage;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The task-run query of the {@code /api/v2/workspace/&#123;workspace&#125;/taskrun} surface: the
 * workspace scoping it shares with the run query, its filters and the shape of a row.
 *
 * <p>The identity is a {@code session}-scope member of two workspaces, as in {@link
 * WorkflowRunWorkspaceQueryTest}, so the workspace narrowing is load-bearing.
 */
class TaskRunWorkspaceQueryTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "taskrun-query-member";
  private static final String MY_WORKSPACE = "taskrun-query-my-ws";
  private static final String SIBLING_WORKSPACE = "taskrun-query-sibling-ws";
  private static final String FOREIGN_WORKSPACE = "taskrun-query-foreign-ws";

  private static final String MY_WORKFLOW = "taskrun-query-my-wf";
  private static final String SIBLING_WORKFLOW = "taskrun-query-sibling-wf";
  private static final String FOREIGN_WORKFLOW = "taskrun-query-foreign-wf";

  @Autowired private WorkflowRunService workflowRunService;

  // A label unique to each test, so rows seeded by another test never match.
  private String version;

  @BeforeEach
  void seedThreeWorkspacesAndAMemberIdentity() {
    version = "v-" + System.nanoTime();
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    for (String workspace : List.of(MY_WORKSPACE, SIBLING_WORKSPACE, FOREIGN_WORKSPACE)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspace,
          workspace,
          Optional.empty(),
          Optional.empty());
    }
    for (String workspace : List.of(MY_WORKSPACE, SIBLING_WORKSPACE)) {
      relationshipService.createEdge(
          RelationshipType.USER,
          MEMBER,
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          workspace,
          Optional.empty());
    }
    workflowOwnedBy(MY_WORKSPACE, MY_WORKFLOW);
    workflowOwnedBy(SIBLING_WORKSPACE, SIBLING_WORKFLOW);
    workflowOwnedBy(FOREIGN_WORKSPACE, FOREIGN_WORKFLOW);

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    principal.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, MY_WORKSPACE, List.of("**/read")),
            new ResolvedPermissions(
                PermissionScope.workspace, SIBLING_WORKSPACE, List.of("**/read"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void aLabelReturnsTheTaskRunsOfEveryRunCarryingItInTheNamedWorkspaceOnly() {
    String parent = run(MY_WORKFLOW);
    String child = run(MY_WORKFLOW);
    String parentTask = taskRun(MY_WORKFLOW, parent, "agents", RunStatus.running, version).getId();
    String childTask = taskRun(MY_WORKFLOW, child, "checklist", RunStatus.running, version).getId();
    taskRun(MY_WORKFLOW, run(MY_WORKFLOW), "checklist", RunStatus.running, "another-version");
    taskRun(SIBLING_WORKFLOW, run(SIBLING_WORKFLOW), "checklist", RunStatus.running, version);
    taskRun(FOREIGN_WORKFLOW, run(FOREIGN_WORKFLOW), "checklist", RunStatus.running, version);

    TaskRunResponsePage page = query(Optional.of(List.of("version%3D" + version)), null, null);

    assertThat(page.getContent())
        .extracting(TaskRun::getId)
        .containsExactlyInAnyOrder(parentTask, childTask);
    assertThat(page.getTotalElements()).isEqualTo(2);
  }

  @Test
  void aRowLeavesOutWhatCanCarryASecretAndCarriesItsAttempt() {
    String runId = run(MY_WORKFLOW);
    TaskRunEntity requeued = taskRun(MY_WORKFLOW, runId, "summarise", RunStatus.failed, version);
    requeued.setParams(
        new LinkedList<>(List.of(new RunParam("apiKey", "s3cr3t", ParamType.secret))));
    requeued.setResults(new LinkedList<>(List.of(new RunResult("answer", null, "s3cr3t"))));
    requeued.getSpec().setScript("echo s3cr3t");
    requeued.setAnnotations(new HashMap<>(Map.of("note", "s3cr3t")));
    requeued.setStatusMessage("refused s3cr3t");
    requeued.setStatusReason("RateLimited");
    RunClaim claim = new RunClaim();
    claim.setBy("dispatcher-a");
    claim.setSeq(3L);
    requeued.setClaim(claim);
    RunRetry retry = new RunRetry();
    retry.setCount(2);
    requeued.setRetry(retry);
    taskRunRepository.save(requeued);
    taskRun(MY_WORKFLOW, runId, "approve", RunStatus.succeeded, version);

    Map<String, TaskRun> rows = new HashMap<>();
    query(Optional.of(List.of("version%3D" + version)), null, null)
        .getContent()
        .forEach(row -> rows.put(row.getName(), row));

    TaskRun row = rows.get("summarise");
    assertThat(row.getParams()).isNull();
    assertThat(row.getResults()).isNull();
    assertThat(row.getSpec()).isNull();
    assertThat(row.getAnnotations()).isNull();
    assertThat(row.getStatusMessage()).isNull();
    assertThat(row.getStatusReason()).isEqualTo("RateLimited");
    assertThat(row.getWorkflowRunRef()).isEqualTo(runId);
    assertThat(row.getLabels()).containsEntry("version", version);
    assertThat(row.getAttempt()).isEqualTo(3);
    assertThat(rows.get("approve").getAttempt()).as("no dispatcher claimed it").isNull();
  }

  @Test
  void aTaskNameMatchesTheNodeAndItsItemsAndAStatusNarrowsThem() {
    String runId = run(MY_WORKFLOW);
    for (String name : List.of("checklist", "checklist[0]", "checklist[1]", "checklist-vote")) {
      taskRun(MY_WORKFLOW, runId, name, RunStatus.succeeded, version);
    }
    taskRun(MY_WORKFLOW, runId, "checklist[2]", RunStatus.failed, version);
    taskRun(MY_WORKFLOW, runId, "review[0]", RunStatus.succeeded, version);

    assertThat(query(Optional.empty(), runId, "checklist").getContent())
        .extracting(TaskRun::getName)
        .containsExactlyInAnyOrder("checklist", "checklist[0]", "checklist[1]", "checklist[2]");
    assertThat(
            workflowRunService
                .queryTaskRuns(
                    MY_WORKSPACE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of("failed")),
                    Optional.empty(),
                    Optional.of(List.of(runId)),
                    Optional.empty(),
                    Optional.of(List.of("checklist")),
                    Optional.empty())
                .getContent())
        .extracting(TaskRun::getName)
        .containsExactly("checklist[2]");
  }

  @Test
  void aForeachParentIsMarkedAndTheForeachFilterKeepsOrLeavesOutParents() {
    String runId = run(MY_WORKFLOW);
    TaskRunEntity parent = taskRun(MY_WORKFLOW, runId, "locate", RunStatus.succeeded, version);
    parent.setForeach(new WorkflowTaskForeach());
    taskRunRepository.save(parent);
    for (int index = 0; index < 2; index++) {
      TaskRunEntity item =
          taskRun(MY_WORKFLOW, runId, "locate[" + index + "]", RunStatus.succeeded, version);
      item.setParentRef(parent.getId());
      item.setIndex(index);
      taskRunRepository.save(item);
    }
    taskRun(MY_WORKFLOW, runId, "publish", RunStatus.succeeded, version);

    TaskRunResponsePage all = query(Optional.empty(), runId, null, Optional.empty());
    assertThat(all.getTotalElements()).isEqualTo(4);
    assertThat(all.getContent())
        .filteredOn(row -> Boolean.TRUE.equals(row.getForeach()))
        .extracting(TaskRun::getName)
        .containsExactly("locate");

    TaskRunResponsePage items = query(Optional.empty(), runId, null, Optional.of(false));
    assertThat(items.getTotalElements()).isEqualTo(3);
    assertThat(items.getContent())
        .extracting(TaskRun::getName)
        .containsExactlyInAnyOrder("locate[0]", "locate[1]", "publish");

    assertThat(query(Optional.empty(), runId, "locate", Optional.of(true)).getContent())
        .extracting(TaskRun::getName)
        .containsExactly("locate");
  }

  @Test
  void pagesFollowCreationThenIdAndCountEveryMatch() {
    String runId = run(MY_WORKFLOW);
    for (String name : List.of("a", "b", "c")) {
      taskRun(MY_WORKFLOW, runId, name, RunStatus.succeeded, version);
    }

    List<String> pagedIds = new LinkedList<>();
    for (int page = 0; page < 3; page++) {
      TaskRunResponsePage one =
          workflowRunService.queryTaskRuns(
              MY_WORKSPACE,
              Optional.empty(),
              Optional.empty(),
              Optional.of(1),
              Optional.of(page),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.of(List.of(runId)),
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
      assertThat(one.getTotalElements()).isEqualTo(3);
      pagedIds.add(one.getContent().get(0).getId());
    }

    assertThat(pagedIds)
        .containsExactlyElementsOf(
            query(Optional.empty(), runId, null).getContent().stream()
                .map(TaskRun::getId)
                .toList())
        .doesNotHaveDuplicates();
  }

  @Test
  void aQueryNamingNeitherALabelNorARunIsRefused() {
    assertThatThrownBy(() -> query(Optional.empty(), null, null))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getCode())
        .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
  }

  @Test
  void aRunOutsideTheWorkspaceCannotBeNamedAndAnUnreachableWorkspaceIs404() {
    String foreignRun = run(FOREIGN_WORKFLOW);
    taskRun(FOREIGN_WORKFLOW, foreignRun, "checklist", RunStatus.succeeded, version);
    String siblingRun = run(SIBLING_WORKFLOW);
    taskRun(SIBLING_WORKFLOW, siblingRun, "checklist", RunStatus.succeeded, version);

    assertThat(query(Optional.empty(), foreignRun, null).getContent()).isEmpty();
    assertThat(query(Optional.empty(), siblingRun, null).getContent()).isEmpty();
    assertThatThrownBy(
            () ->
                workflowRunService.queryTaskRuns(
                    FOREIGN_WORKSPACE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of("version%3D" + version)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getReason())
        .isEqualTo(BoomerangError.WORKSPACE_INVALID_REF.getReason());
  }

  // The member's own workspace, by label or by one run, optionally by one task name.
  private TaskRunResponsePage query(Optional<List<String>> labels, String runId, String task) {
    return query(labels, runId, task, Optional.empty());
  }

  private TaskRunResponsePage query(
      Optional<List<String>> labels, String runId, String task, Optional<Boolean> foreach) {
    return workflowRunService.queryTaskRuns(
        MY_WORKSPACE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        labels,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(runId).map(List::of),
        Optional.empty(),
        Optional.ofNullable(task).map(List::of),
        foreach);
  }

  private String run(String workflowRef) {
    return savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running).getId();
  }

  // A task run carrying its run's version label, as the engine copies a run's labels onto it.
  private TaskRunEntity taskRun(
      String workflowRef, String runId, String name, RunStatus status, String versionLabel) {
    TaskRunEntity task =
        savedTaskRun(name, TaskType.template, status, RunPhase.completed, workflowRef, runId);
    task.getLabels().put("version", versionLabel);
    return taskRunRepository.save(task);
  }

  private void workflowOwnedBy(String workspace, String workflowRef) {
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowRef,
        workflowRef,
        Optional.empty(),
        Optional.empty());
  }
}
