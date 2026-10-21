package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.*;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.SettingsService;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.GraphProcessor;
import io.boomerang.engine.ResultUtil;
import io.boomerang.workflow.TaskService;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class DAGUtility {
  private static final Logger LOGGER = LogManager.getLogger();

  private final TaskRunRepository taskRunRepository;
  private final TaskService taskService;
  private final MongoTemplate mongoTemplate;
  private final SettingsService settingsService;

  public DAGUtility(
      TaskRunRepository taskRunRepository,
      TaskService taskService,
      MongoTemplate mongoTemplate,
      SettingsService settingsService) {
    this.taskRunRepository = taskRunRepository;
    this.taskService = taskService;
    this.mongoTemplate = mongoTemplate;
    this.settingsService = settingsService;
  }

  /**
   * The operator's task settings a new TaskRun takes when its task sets none: the per-task timeout
   * ceiling in minutes (0, or blank, for none - the seed ships 0 so a task inherits its run's
   * timeout), the image for a task that names none, and the pod deletion policy.
   */
  private record TaskDefaults(long timeout, String image, TaskDeletion deletion) {}

  private TaskDefaults taskDefaults() {
    Map<String, String> task =
        settingsService.getSettingByKey("task").getConfig().stream()
            .filter(config -> config.getValue() != null)
            .collect(Collectors.toMap(SettingConfig::getKey, SettingConfig::getValue, (a, b) -> a));
    String timeout = task.get("default.timeout");
    String deletion = task.get("deletion.policy");
    return new TaskDefaults(
        (timeout != null && !timeout.isBlank()) ? Long.parseLong(timeout) : 0L,
        task.get("default.image"),
        (deletion != null) ? TaskDeletion.getDeletion(deletion) : TaskDeletion.Never);
  }

  public boolean validateWorkflow(WorkflowRunEntity wfRunEntity, List<TaskRunEntity> tasks) {
    if (tasks.size() == 2) {
      // Workflow only has Start and End and therefore cant run.
      return false;
    }
    final TaskRunEntity start =
        tasks.stream().filter(tsk -> TaskType.start.equals(tsk.getType())).findAny().orElse(null);
    final TaskRunEntity end =
        tasks.stream().filter(tsk -> TaskType.end.equals(tsk.getType())).findAny().orElse(null);
    Graph<String, DefaultEdge> graph = this.createGraph(tasks);
    updateGraphWithTaskRunStatus(graph, tasks);
    DijkstraShortestPath<String, DefaultEdge> dijkstraAlg = new DijkstraShortestPath<>(graph);
    final SingleSourcePaths<String, DefaultEdge> pathFromStart =
        dijkstraAlg.getPaths(start.getId());
    return (pathFromStart.getPath(end.getId()) != null);
  }

  public Graph<String, DefaultEdge> createGraph(List<TaskRunEntity> tasks) {
    final List<String> vertices =
        tasks.stream().map(TaskRunEntity::getId).collect(Collectors.toList());
    final Map<String, String> idByName =
        tasks.stream()
            .collect(Collectors.toMap(TaskRunEntity::getName, TaskRunEntity::getId, (a, b) -> a));

    final List<Pair<String, String>> edgeList = new LinkedList<>();
    for (final TaskRunEntity task : tasks) {
      for (final WorkflowTaskDependency dep : task.getDependencies()) {
        String depTaskRefAsId = idByName.get(dep.getTaskRef());
        if (depTaskRefAsId == null) {
          throw new BoomerangException(
              BoomerangError.WORKFLOWRUN_INVALID_DEPENDENCY, dep.getTaskRef());
        }
        edgeList.add(Pair.of(depTaskRefAsId, task.getId()));
      }
    }
    return GraphProcessor.createGraph(vertices, edgeList);
  }

  /**
   * Return the run's graph: its TaskRuns without a parent, holding only the fields the graph reads.
   * A foreach task's items are never vertices, its parent stands for them. Status is read live when
   * the graph is walked. The result is never saved - a save would drop every field left out.
   */
  public List<TaskRunEntity> retrieveGraph(String wfRunId) {
    Query query =
        Query.query(Criteria.where("workflowRunRef").is(wfRunId).and("parentRef").is(null));
    query
        .fields()
        .include("name")
        .include("type")
        .include("dependencies")
        .include("phase")
        .include("foreach")
        .include("workflowRunRef");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // TODO: determine a better way to handle the start and end task without saving them as a
  // TaskRunEntity
  public List<TaskRunEntity> createTaskList(
      WorkflowRevisionEntity wfRevisionEntity, WorkflowRunEntity wfRunEntity) {
    final List<TaskRunEntity> taskList = new LinkedList<>();
    // Read once, and only when a TaskRun is created: a run's TaskRuns are all created at queue.
    TaskDefaults defaults = null;
    // One fetch of the run's existing TaskRuns keyed by name - the (workflowRunRef, name) unique
    // index guarantees one per name, so no per-task find-first query is needed.
    final Map<String, TaskRunEntity> existingByName =
        taskRunRepository.findByWorkflowRunRefAndParentRefIsNull(wfRunEntity.getId()).stream()
            .collect(Collectors.toMap(TaskRunEntity::getName, t -> t, (a, b) -> a));
    for (final WorkflowTask wfRevisionTask : wfRevisionEntity.getTasks()) {
      TaskRunEntity existingTaskRunEntity = existingByName.get(wfRevisionTask.getName());
      if (existingTaskRunEntity != null) {
        taskList.add(existingTaskRunEntity);
      } else {
        LOGGER.debug(
            "[{}] Creating TaskRunEntity: {}", wfRunEntity.getId(), wfRevisionTask.getName());
        TaskRunEntity taskRunEntity = new TaskRunEntity();
        taskRunEntity.setName(wfRevisionTask.getName());
        taskRunEntity.setStatus(RunStatus.notstarted);
        taskRunEntity.setPhase(RunPhase.pending);
        if (TaskType.start.equals(wfRevisionTask.getType())) {
          taskRunEntity.setStatus(RunStatus.succeeded);
          taskRunEntity.setPhase(RunPhase.completed);
        }
        taskRunEntity.setType(wfRevisionTask.getType());
        taskRunEntity.setCreationDate(new Date());
        taskRunEntity.setTaskVersion(wfRevisionTask.getTaskVersion());
        taskRunEntity.setAnnotations(wfRevisionTask.getAnnotations());
        taskRunEntity.setDependencies(wfRevisionTask.getDependencies());
        taskRunEntity.setWorkflowRef(wfRevisionEntity.getWorkflowRef());
        taskRunEntity.setWorkflowRevisionRef(wfRevisionEntity.getId());
        taskRunEntity.setWorkflowRunRef(wfRunEntity.getId());

        if (!TaskType.start.equals(wfRevisionTask.getType())
            && !TaskType.end.equals(wfRevisionTask.getType())) {

          if (defaults == null) {
            defaults = taskDefaults();
          }
          Task task = taskService.retrieveAndValidateTask(wfRevisionTask);
          taskRunEntity.setTaskRef(wfRevisionTask.getTaskRef());
          // Only a task that runs can run for each item; start and end never carry the setting.
          taskRunEntity.setForeach(wfRevisionTask.getForeach());
          taskRunEntity.setTaskVersion(task.getVersion());
          LOGGER.debug(
              "[{}] Found Task: {} @ {}", wfRunEntity.getId(), task.getName(), task.getVersion());

          // Stack the labels based on label propagation
          // Task Template -> Workflow Task -> Run
          taskRunEntity.getLabels().putAll(task.getLabels());
          taskRunEntity.getLabels().putAll(wfRevisionTask.getLabels());
          taskRunEntity.getLabels().putAll(wfRunEntity.getLabels());


          // Set Task RunResults
          taskRunEntity.setResults(ResultUtil.resultSpecToRunResult(task.getSpec().getResults()));
          if (TaskType.script.equals(wfRevisionTask.getType())
              || TaskType.custom.equals(wfRevisionTask.getType())
              || TaskType.generic.equals(wfRevisionTask.getType())) {
            taskRunEntity.setResults(ResultUtil.resultSpecToRunResult(wfRevisionTask.getResults()));
          }

          // Set Task RunParams
          if (task.getSpec().getParams() != null && !task.getSpec().getParams().isEmpty()) {
            LOGGER.debug(
                "[{}] Task Template Params: {}",
                wfRunEntity.getId(),
                ParameterUtil.abstractParamNames(task.getSpec().getParams()));
            LOGGER.debug(
                "[{}] Revision Task Params: {}",
                wfRunEntity.getId(),
                ParameterUtil.runParamNames(wfRevisionTask.getParams()));
            taskRunEntity.setParams(
                ParameterUtil.addUniqueParams(
                    ParameterUtil.abstractParamToRunParam(task.getSpec().getParams()),
                    wfRevisionTask.getParams()));
          } else {
            LOGGER.debug(
                "[{}] Task Template Params: {}",
                wfRunEntity.getId(),
                ParameterUtil.runParamNames(wfRevisionTask.getParams()));
            taskRunEntity.setParams(wfRevisionTask.getParams());
          }
          LOGGER.debug(
              "[{}] Task Run Params: {}",
              wfRunEntity.getId(),
              ParameterUtil.runParamNames(taskRunEntity.getParams()));
          // Timeout, in minutes, is the smallest of three ceilings, in this order:
          //   1. the platform default (the task settings' default.timeout),
          //   2. the task's own declared timeout, when set and not greater,
          //   3. the run's own timeout, when set and not greater.
          // 0 at any step means "unguarded" and imposes no ceiling; a task ends up at 0 only when
          // all three are 0. The run is a ceiling because a guard must cover the
          // work beneath it, so a task must never outlive the run guard around it. Without this the
          // run watcher reaps a healthy task at the run deadline while that task still believes it
          // has budget left — and on a fresh install it would, because the seeded per-task ceiling
          // is a per-pod limit, not a budget each task is entitled to spend.
          long timeout = defaults.timeout();
          if (wfRevisionTask.getTimeout() != null
              && wfRevisionTask.getTimeout() > 0
              && (timeout <= 0 || wfRevisionTask.getTimeout() < timeout)) {
            timeout = wfRevisionTask.getTimeout();
          }
          if (wfRunEntity.getTimeout() != null
              && wfRunEntity.getTimeout() > 0
              && (timeout <= 0 || wfRunEntity.getTimeout() < timeout)) {
            timeout = wfRunEntity.getTimeout();
          }
          taskRunEntity.setTimeout(timeout);

          // A Task mounts the run's workspace unless it opts out (workspace: false); a Task
          // authored on the canvas cannot opt out and always mounts it. The TaskRun carries its
          // own copy with the mountPath settled, so the executor never has to default it.
          if (wfRunEntity.getWorkspace() != null
              && !Boolean.FALSE.equals(wfRevisionTask.getWorkspace())) {
            WorkflowWorkspace runWorkspace = wfRunEntity.getWorkspace();
            WorkflowWorkspace taskWorkspace = new WorkflowWorkspace();
            taskWorkspace.setSize(runWorkspace.getSize());
            taskWorkspace.setClassName(runWorkspace.getClassName());
            taskWorkspace.setAccessMode(runWorkspace.getAccessMode());
            taskWorkspace.setMountPath(runWorkspace.mountPathOrDefault());
            taskRunEntity.setWorkspace(taskWorkspace);
          }

          // Set TaskRun Spec from Task Spec - Debug and Deletion come from an alternate
          // source
          if (!Objects.isNull(task.getSpec().getImage()) && !task.getSpec().getImage().isEmpty()) {
            taskRunEntity.getSpec().setImage(task.getSpec().getImage());
          } else if (TaskType.template.equals(wfRevisionTask.getType())
              || TaskType.script.equals(wfRevisionTask.getType())) {
            taskRunEntity.getSpec().setImage(defaults.image());
          }
          if (!Objects.isNull(task.getSpec().getCommand())) {
            taskRunEntity.getSpec().setCommand(task.getSpec().getCommand());
          }
          if (!Objects.isNull(task.getSpec().getArguments())) {
            taskRunEntity.getSpec().setArguments(task.getSpec().getArguments());
          }
          if (!Objects.isNull(task.getSpec().getEnvs())) {
            taskRunEntity.getSpec().setEnvs(task.getSpec().getEnvs());
          }
          if (!Objects.isNull(task.getSpec().getScript())) {
            taskRunEntity.getSpec().setScript(task.getSpec().getScript());
          }
          if (!Objects.isNull(task.getSpec().getWorkingDir())) {
            taskRunEntity.getSpec().setWorkingDir(task.getSpec().getWorkingDir());
          }
          if (!Objects.isNull(task.getSpec().getAdditionalProperties())) {
            taskRunEntity
                .getSpec()
                .getAdditionalProperties()
                .putAll(task.getSpec().getAdditionalProperties());
          }
          // Custom tasks carry their runtime spec as declared params rather than a catalogue
          // Task Spec - map them onto the TaskRun spec the dispatcher reads, before param
          // resolution runs so $(params.x) references inside them still substitute.
          if (TaskType.custom.equals(wfRevisionTask.getType())) {
            applyCustomTaskSpecFromParams(taskRunEntity);
          }
          taskRunEntity.getSpec().setDeletion(defaults.deletion());
          if (!Objects.isNull(wfRunEntity.getDebug())) {
            taskRunEntity.getSpec().setDebug(wfRunEntity.getDebug());
          }
        }
        taskRunRepository.save(taskRunEntity);
        LOGGER.debug(
            "[{}] TaskRunEntity ({}) created for: {}",
            wfRunEntity.getId(),
            taskRunEntity.getId(),
            taskRunEntity.getName());
        taskList.add(taskRunEntity);
      }
    }
    LOGGER.info(
        "[{}] Task List: {}",
        wfRunEntity.getId(),
        taskList.stream().map(TaskRunEntity::getName).collect(Collectors.joining(", ")));
    return taskList;
  }

  /*
   * Determine if there is a valid path for this task taking into account dependency and execution conditions
   */
  public boolean canRunTask(List<TaskRunEntity> tasks, TaskRunEntity current) {
    final TaskRunEntity start = this.getTaskByType(tasks, TaskType.start);
    Graph<String, DefaultEdge> graph = this.createGraph(tasks);
    updateGraphWithTaskRunStatus(graph, tasks);
    // After graph has been updated and edges removed per
    // individual task conditions. Validate that all conditions are met for current task
    // Remove or gate this call if you want an OR rather than an AND on dependencies.
    allDependenciesValid(graph, current);
    DijkstraShortestPath<String, DefaultEdge> dijkstraAlg = new DijkstraShortestPath<>(graph);
    final SingleSourcePaths<String, DefaultEdge> pathFromStart =
        dijkstraAlg.getPaths(start.getId());
    return (pathFromStart.getPath(current.getId()) != null);
  }

  public TaskRunEntity getTaskByType(List<TaskRunEntity> tasks, TaskType type) {
    return tasks.stream().filter(tsk -> type.equals(tsk.getType())).findAny().orElse(null);
  }

  /*
   * A custom task's image/command/arguments/script are declared params (image, command,
   * arguments, shellScript), not a catalogue Task Spec - matched by name case-insensitively. A
   * blank or absent image leaves spec.image untouched so the dispatcher's NO_TASK_IMAGE guard
   * still fires; a blank or absent param otherwise leaves the catalogue spec value in place.
   *
   * A secret-typed one of these params is copied as its reference, $(params.<name>), never its
   * value: the stored spec holds references only, and the value is filled in at claim. A secret
   * command or arguments therefore arrives as one element, not split into lines.
   */
  private void applyCustomTaskSpecFromParams(TaskRunEntity taskRunEntity) {
    RunParam image = findParamByName(taskRunEntity.getParams(), "image");
    if (hasValue(image)) {
      taskRunEntity.getSpec().setImage(specValue(image));
    }
    RunParam command = findParamByName(taskRunEntity.getParams(), "command");
    if (hasValue(command)) {
      taskRunEntity.getSpec().setCommand(specLines(command));
    }
    RunParam arguments = findParamByName(taskRunEntity.getParams(), "arguments");
    if (hasValue(arguments)) {
      taskRunEntity.getSpec().setArguments(specLines(arguments));
    }
    RunParam shellScript = findParamByName(taskRunEntity.getParams(), "shellScript");
    if (hasValue(shellScript)) {
      taskRunEntity.getSpec().setScript(specValue(shellScript));
    }
  }

  private RunParam findParamByName(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equalsIgnoreCase(p.getName()))
        .filter(p -> p.getValue() != null)
        .findFirst()
        .orElse(null);
  }

  private static boolean hasValue(RunParam param) {
    return param != null && !param.getValue().toString().isBlank();
  }

  // The value copied into the spec: the param's own value, or for a secret its reference.
  private static String specValue(RunParam param) {
    return ParamType.secret.equals(param.getType())
        ? "$(params." + param.getName() + ")"
        : param.getValue().toString();
  }

  private List<String> specLines(RunParam param) {
    return ParamType.secret.equals(param.getType())
        ? List.of(specValue(param))
        : splitParamLines(param.getValue().toString());
  }

  private List<String> splitParamLines(String value) {
    return Arrays.stream(value.split("\\R"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.toList());
  }

  // Implement an AND check - all dependencies are met (all paths exist)
  // Validates that there are the same number of edges remaining as dependencies
  // If not, remove all edges. There will then no longer be a shortest path.
  private void allDependenciesValid(Graph<String, DefaultEdge> graph, TaskRunEntity current) {
    int noOfEdges = graph.inDegreeOf(current.getId());
    LOGGER.debug("Dependencies met: {} <= {}", noOfEdges, current.getDependencies().size());
    if (noOfEdges != current.getDependencies().size()) {
      // Copy set to not get a ConcurrentModificationException
      final Set<DefaultEdge> edgesToRemove = Set.copyOf(graph.incomingEdgesOf(current.getId()));
      graph.removeAllEdges(edgesToRemove);
    }
  }

  // One projected read of the run's live TaskRuns (only phase/status/decisionValue are needed)
  // keyed by id - as fresh as a per-node findById, but a single query instead of O(N).
  private Map<String, TaskRunEntity> liveTaskRunPhasesById(List<TaskRunEntity> tasks) {
    if (tasks.isEmpty()) {
      return Map.of();
    }
    Query query =
        Query.query(Criteria.where("workflowRunRef").is(tasks.get(0).getWorkflowRunRef()));
    query.fields().include("_id").include("phase").include("status").include("decisionValue");
    return mongoTemplate.find(query, TaskRunEntity.class).stream()
        .collect(Collectors.toMap(TaskRunEntity::getId, t -> t, (a, b) -> a));
  }

  private void updateGraphWithTaskRunStatus(
      Graph<String, DefaultEdge> graph, List<TaskRunEntity> tasks) {
    final Map<String, TaskRunEntity> liveById = liveTaskRunPhasesById(tasks);
    TopologicalOrderIterator<String, DefaultEdge> orderIterator =
        new TopologicalOrderIterator<>(graph);
    while (orderIterator.hasNext()) {
      final String taskId = orderIterator.next();
      TaskRunEntity currentTask = this.getTaskById(tasks, taskId);
      if (!TaskType.start.equals(currentTask.getType())
          && !TaskType.end.equals(currentTask.getType())) {

        TaskRunEntity liveTask = liveById.get(currentTask.getId());

        if (liveTask != null) {
          RunPhase taskRunPhase = liveTask.getPhase();
          LOGGER.debug("[{}] Phase: {}", taskId, taskRunPhase.toString());
          if (RunPhase.completed.equals(taskRunPhase)) {
            if (TaskType.decision.equals(currentTask.getType())
                && RunStatus.succeeded.equals(liveTask.getStatus())) {
              String decisionValue = liveTask.getDecisionValue();
              processDecision(graph, tasks, decisionValue, currentTask.getId(), currentTask);
            } else if (TaskType.decision.equals(currentTask.getType())) {
              // A switch that never ran - skipped, or failed at admission - made no decision, so it
              // takes no branch: what is reachable only through it is skipped.
              graph.removeAllEdges(Set.copyOf(graph.outgoingEdgesOf(currentTask.getId())));
            } else {
              currentTask.setStatus(liveTask.getStatus());
              this.updateTaskInGraph(graph, tasks, currentTask);
            }
          }
        }
      }
    }
  }

  /*
   * Get the decision value and map the edges to next nodes
   * If next nodes don't have an edge with the correct value, then remove the edge (and hence no path will be found)
   */
  private void processDecision(
      Graph<String, DefaultEdge> graph,
      List<TaskRunEntity> tasksToRun,
      String value,
      final String currentVertex,
      TaskRunEntity currentTask) {
    List<String> matchedNodes =
        calculateMatchedNodes(graph, tasksToRun, value, currentVertex, currentTask);
    LOGGER.debug("Nodes Matched: {}", matchedNodes.toString());
    Iterator<DefaultEdge> itrerator = graph.edgesOf(currentVertex).iterator();
    while (itrerator.hasNext()) {
      DefaultEdge e = itrerator.next();
      String destination = graph.getEdgeTarget(e);
      String source = graph.getEdgeSource(e);
      if (source.equals(currentVertex)
          && matchedNodes.stream().noneMatch(str -> str.trim().equals(destination))) {
        LOGGER.debug("Removing Edge: {}", e.toString());
        graph.removeEdge(e);
      }
    }
  }

  private List<String> calculateMatchedNodes(
      Graph<String, DefaultEdge> graph,
      List<TaskRunEntity> tasksToRun,
      String value,
      final String currentVert,
      TaskRunEntity currentTask) {
    Set<DefaultEdge> outgoingEdges = graph.outgoingEdgesOf(currentVert);

    List<String> matchedNodes = new LinkedList<>();
    List<String> defaultNodes = new LinkedList<>();

    for (DefaultEdge edge : outgoingEdges) {
      String destination = graph.getEdgeTarget(edge);
      TaskRunEntity destTask =
          tasksToRun.stream().filter(t -> t.getId().equals(destination)).findFirst().orElse(null);
      if (destTask != null) {
        Optional<WorkflowTaskDependency> optionalDependency =
            getOptionalDependency(currentTask.getName(), destTask);
        if (optionalDependency.isPresent()) {
          WorkflowTaskDependency dependency = optionalDependency.get();
          String linkValue = dependency.getDecisionCondition();
          String node = destTask.getId();
          boolean matched = false;

          if (linkValue != null && !linkValue.isEmpty()) {
            String[] lines = linkValue.split("\\r?\\n");
            for (String line : lines) {
              String patternString = line;
              Pattern pattern = Pattern.compile(patternString);
              Matcher matcher = pattern.matcher(value);
              if (matcher.matches()) {
                matched = true;
              }
            }
            if (matched) {
              matchedNodes.add(node);
            }
            LOGGER.debug(
                "[{}] Matched: {}, Decision Value: {}, Condition: {}",
                currentVert,
                matched,
                value,
                linkValue);
          } else {
            defaultNodes.add(node);
          }
        }
      }
    }
    if (matchedNodes.isEmpty()) {
      return defaultNodes;
    }
    return matchedNodes;
  }

  private void updateTaskInGraph(
      Graph<String, DefaultEdge> graph, List<TaskRunEntity> tasksToRun, TaskRunEntity currentTask) {
    String currentVert = currentTask.getId();
    List<String> matchedNodes = new LinkedList<>();
    Set<DefaultEdge> outgoingEdges = graph.outgoingEdgesOf(currentVert);
    RunStatus status = currentTask.getStatus();

    for (DefaultEdge edge : outgoingEdges) {
      String destination = graph.getEdgeTarget(edge);
      TaskRunEntity destTask =
          tasksToRun.stream().filter(t -> t.getId().equals(destination)).findFirst().orElse(null);
      if (destTask != null) {
        Optional<WorkflowTaskDependency> optionalDependency =
            getOptionalDependency(currentTask.getName(), destTask);
        if (optionalDependency.isPresent()) {
          WorkflowTaskDependency dependency = optionalDependency.get();
          ExecutionCondition condition = dependency.getExecutionCondition();
          String node = destTask.getId();
          if (condition != null
                  && (RunStatus.failed.equals(status)
                      && ExecutionCondition.failure.equals(condition))
              || (RunStatus.succeeded.equals(status)
                  && ExecutionCondition.success.equals(condition))
              || (ExecutionCondition.always.equals(condition))) {
            matchedNodes.add(node);
          }
        }
      }
    }

    Iterator<DefaultEdge> itrerator = graph.edgesOf(currentVert).iterator();
    while (itrerator.hasNext()) {
      DefaultEdge e = itrerator.next();
      String destination = graph.getEdgeTarget(e);
      String source = graph.getEdgeSource(e);
      if (source.equals(currentVert)
          && matchedNodes.stream().noneMatch(str -> str.trim().equals(destination))) {
        graph.removeEdge(e);
      }
    }
  }

  private Optional<WorkflowTaskDependency> getOptionalDependency(
      final String currentVert, TaskRunEntity destTask) {
    Optional<WorkflowTaskDependency> optionalDependency =
        destTask.getDependencies().stream()
            .filter(d -> d.getTaskRef().equals(currentVert))
            .findAny();
    return optionalDependency;
  }

  private TaskRunEntity getTaskById(List<TaskRunEntity> tasks, String id) {
    return tasks.stream().filter(tsk -> id.equals(tsk.getId())).findAny().orElse(null);
  }

  public List<TaskRunEntity> getTasksDependants(
      List<TaskRunEntity> tasks, TaskRunEntity currentTask) {
    return tasks.stream()
        .filter(
            t ->
                t.getDependencies().stream()
                    .anyMatch(d -> d.getTaskRef().equals(currentTask.getName())))
        .collect(Collectors.toList());
  }
}
