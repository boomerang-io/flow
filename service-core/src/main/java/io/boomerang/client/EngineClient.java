package io.boomerang.client;

import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowCount;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTemplate;
import io.boomerang.engine.TaskRunService;
import io.boomerang.workflow.TaskDefinitionService;
import io.boomerang.engine.WorkflowRunService;
import io.boomerang.workflow.WorkflowDefinitionService;
import io.boomerang.workflow.WorkflowTemplateService;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.api.model.TaskResponsePage;
import io.boomerang.api.model.WorkflowResponsePage;
import io.boomerang.api.model.WorkflowRunResponsePage;
import io.boomerang.api.model.WorkflowTemplateResponsePage;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/*
 * This facade used to be the flow -> engine HTTP client (RestTemplate calls against
 * flow.engine.*.url endpoints). Now that service-flow and service-engine are merged into the
 * single service-core deployable (DD-02), every method below calls the equivalent engine service
 * directly, in-process. The class/method signatures are preserved unchanged so the ~consumers
 * across the flow services keep compiling untouched; only the transport died.
 *
 * Engine services throw BoomerangException on failure - that propagates naturally to callers
 * exactly as it does from any other in-module service call. There is no more
 * "Exception in communicating with internal services" 500 wrapper: that wrapper only ever fired
 * for RestTemplate/network failures, which cannot happen for an in-process call, and it used to
 * flatten the engine's real BoomerangException (e.g. a 400) into a generic 500 - that flattening
 * is gone, which is a behaviour improvement, not a regression.
 */
@Service
@Primary
public class EngineClient {

  private static final Logger LOGGER = LogManager.getLogger(EngineClient.class);

  @Autowired private WorkflowRunService workflowRunService;

  @Autowired private WorkflowDefinitionService workflowService;

  @Autowired private TaskRunService taskRunService;

  @Autowired private TaskDefinitionService taskService;

  @Autowired private WorkflowTemplateService workflowTemplateService;

  /*
   * ************************************** WorkflowRun endpoints
   * **************************************
   */
  public WorkflowRun getWorkflowRun(String workflowRunId, boolean withTasks) {
    return workflowRunService.get(workflowRunId, withTasks);
  }

  public WorkflowRunResponsePage queryWorkflowRuns(
      Optional<Long> fromDate,
      Optional<Long> toDate,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTriggers) {
    Page<WorkflowRun> page =
        workflowRunService.query(
            fromDate.map(Date::new),
            toDate.map(Date::new),
            queryLimit,
            queryPage,
            querySort,
            queryLabels,
            queryStatus,
            queryPhase,
            queryWorkflowRuns,
            queryWorkflows,
            queryTriggers);
    return new WorkflowRunResponsePage(
        page.getContent(), page.getPageable(), page.getTotalElements());
  }

  public WorkflowRunInsight insightWorkflowRuns(
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<Long> fromDate,
      Optional<Long> toDate) {
    return workflowRunService.insights(
        fromDate.map(Date::new), toDate.map(Date::new), queryLabels, queryWorkflowRuns, queryWorkflows);
  }

  public WorkflowRunCount countWorkflowRuns(
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows,
      Optional<Long> fromDate,
      Optional<Long> toDate) {
    return workflowRunService.count(
        fromDate.map(Date::new), toDate.map(Date::new), queryLabels, queryWorkflows);
  }

  public WorkflowRun startWorkflowRun(String workflowRunId, Optional<WorkflowRunRequest> request) {
    return workflowRunService.start(workflowRunId, request);
  }

  public WorkflowRun finalizeWorkflowRun(String workflowRunId) {
    return workflowRunService.finalize(workflowRunId);
  }

  public WorkflowRun cancelWorkflowRun(String workflowRunId) {
    return workflowRunService.cancel(workflowRunId);
  }

  public WorkflowRun pauseWorkflowRun(String workflowRunId) {
    return workflowRunService.pause(workflowRunId);
  }

  public WorkflowRun resumeWorkflowRun(String workflowRunId) {
    return workflowRunService.resume(workflowRunId);
  }

  public WorkflowRun retryWorkflowRun(String workflowRunId) {
    // Matches the wire shape this facade always sent: PUT .../retry with no ?start query param,
    // i.e. start=false, and the engine controller's hardcoded initial retryCount of 1.
    return workflowRunService.retry(workflowRunId, false, 1);
  }

  public void deleteWorkflowRun(String workflowRunId) {
    workflowRunService.delete(workflowRunId);
  }

  public void eventWorkflowRun(String workflowRunId, WorkflowRunEventRequest request) {
    // Flow's former WorkflowRunEventRequest (io.boomerang.workflow.model) was deleted (P2b
    // package-move-map.md) in favour of this, the Engine's superset - the two were structurally
    // identical except for the Engine's optional transport dedup id, which this facade never
    // populated over the wire anyway, so no conversion is needed any more.
    workflowRunService.event(workflowRunId, request);
  }

  /*
   * ************************************** Workflow endpoints
   * **************************************
   */

  public Workflow getWorkflow(String workflowId, Optional<Integer> version, boolean withTasks) {
    return workflowService.get(workflowId, version, withTasks).getBody();
  }

  public WorkflowResponsePage queryWorkflows(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryWorkflows) {
    Page<Workflow> page =
        workflowService.query(queryLimit, queryPage, querySort, queryLabels, queryStatus, queryWorkflows);
    return new WorkflowResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());
  }

  public WorkflowCount countWorkflows(
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows,
      Optional<Long> fromDate,
      Optional<Long> toDate) {
    return workflowService
        .count(fromDate.map(Date::new), toDate.map(Date::new), queryLabels, queryWorkflows)
        .getBody();
  }

  public Workflow createWorkflow(Workflow workflow) {
    return workflowService.create(workflow, false).getBody();
  }

  public Workflow applyWorkflow(Workflow workflow, boolean replace) {
    return workflowService.apply(workflow, replace).getBody();
  }

  public WorkflowRun submitWorkflow(
      String workflowId, WorkflowSubmitRequest request, boolean start) {
    return workflowService.submit(workflowId, request, start);
  }

  public List<ChangeLogVersion> getWorkflowChangeLog(String workflowId) {
    return workflowService.changelog(workflowId).getBody();
  }

  public void enableWorkflow(String workflowId) {
    // Dead code path: no io.boomerang.engine controller/service has ever exposed an
    // enable/disable Workflow operation (verified - no callers anywhere in the codebase either).
    // The old flow.engine.workflow.enable.url pointed at a route that did not exist on the
    // engine side, so this call always failed at the HTTP layer; it now fails deterministically
    // instead of over the wire.
    throw new BoomerangException(
        HttpStatus.NOT_IMPLEMENTED.value(),
        "ENGINE_OPERATION_NOT_IMPLEMENTED",
        "No Engine service exists for Workflow enable - this operation is not implemented.",
        HttpStatus.NOT_IMPLEMENTED);
  }

  public void disableWorkflow(String workflowId) {
    // See enableWorkflow() - same dead code path, no backing Engine capability.
    throw new BoomerangException(
        HttpStatus.NOT_IMPLEMENTED.value(),
        "ENGINE_OPERATION_NOT_IMPLEMENTED",
        "No Engine service exists for Workflow disable - this operation is not implemented.",
        HttpStatus.NOT_IMPLEMENTED);
  }

  public void deleteWorkflow(String workflowId) {
    // The previous HTTP call always appended ?cascade=false, but WorkflowControllerV1#deleteWorkflow
    // never accepted a "cascade" param (Spring silently ignored the unknown query param), so the
    // Engine has only ever performed its one delete behaviour - the tombstone status flip.
    workflowService.delete(workflowId);
  }

  /*
   * ************************************** TaskRun endpoints **************************************
   */
  public TaskRun getTaskRun(String taskRunId) {
    return taskRunService.get(taskRunId).getBody();
  }

  public TaskRun endTaskRun(String taskRunId, TaskRunEndRequest request) {
    return taskRunService.end(taskRunId, Optional.ofNullable(request)).getBody();
  }

  public StreamingResponseBody streamTaskRunLog(String taskRunId) {
    // TaskRunService.streamLog already returns the ready-to-write StreamingResponseBody (it
    // delegates to LogClient, which remains on HTTP - that is the separate agent log-stream
    // endpoint, out of scope here), so no adaptation is needed at all.
    LOGGER.info("Starting TaskRun[{}] log stream...", taskRunId);
    return taskRunService.streamLog(taskRunId);
  }

  /*
   * ************************************** Task endpoints
   * **************************************
   */

  public Task getTask(String ref, Optional<Integer> version) {
    return taskService.get(ref, version);
  }

  public TaskResponsePage queryTask(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      List<String> queryRefs) {
    // The old URL builder always appended an "ids" query param (even for an empty list), so the
    // Engine's queryIds Optional was always present - preserved here as Optional.of(queryRefs)
    // rather than being conditioned on emptiness.
    Page<Task> page =
        taskService.query(
            queryLimit, queryPage, querySort, queryLabels, queryStatus, Optional.empty(), Optional.of(queryRefs));
    return new TaskResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());
  }

  public Task createTask(Task request) {
    return taskService.create(request);
  }

  public Task applyTask(Task task, boolean replace) {
    return taskService.apply(task, replace);
  }

  public List<ChangeLogVersion> getTaskChangeLog(String ref) {
    return taskService.changelog(ref);
  }

  public ResponseEntity<Void> deleteTask(String ref) {
    // TaskControllerV1#delete returns void with no explicit status, which Spring MVC serializes
    // as 200 OK over HTTP - matched here directly rather than via a network round trip.
    taskService.delete(ref);
    return ResponseEntity.ok().build();
  }

  /*
   * **************************************
   * WorkflowTemplate endpoints
   * **************************************
   */

  public WorkflowTemplate getWorkflowTemplate(
      String name, Optional<Integer> version, boolean withTasks) {
    // WorkflowTemplateControllerV1#get never accepted the withTasks param either - it always
    // called workflowTemplateService.get(name, version, false) regardless of what the caller
    // asked for. Preserved here bug-for-bug: withTasks is intentionally ignored.
    return workflowTemplateService.get(name, version, false);
  }

  public WorkflowTemplateResponsePage queryWorkflowTemplates(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryNames) {
    Page<WorkflowTemplate> page =
        workflowTemplateService.query(queryLimit, queryPage, querySort, queryLabels, queryNames);
    return new WorkflowTemplateResponsePage(
        page.getContent(), page.getPageable(), page.getTotalElements());
  }

  public WorkflowTemplate createWorkflowTemplate(WorkflowTemplate workflow) {
    return workflowTemplateService.create(workflow);
  }

  public WorkflowTemplate applyWorkflowTemplate(WorkflowTemplate workflow, boolean replace) {
    return workflowTemplateService.apply(workflow, replace);
  }

  public ResponseEntity<Void> deleteWorkflowTemplate(String name) {
    // WorkflowTemplateControllerV1#delete explicitly returns 204 No Content - matched here.
    workflowTemplateService.delete(name);
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}
