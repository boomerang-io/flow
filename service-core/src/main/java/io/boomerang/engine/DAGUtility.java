package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.*;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.GraphProcessor;
import io.boomerang.engine.ResultUtil;
import io.boomerang.workflow.TaskService;
import java.util.Date;
import java.util.HashMap;
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

  public DAGUtility(
      TaskRunRepository taskRunRepository,
      TaskService taskService,
      MongoTemplate mongoTemplate) {
    this.taskRunRepository = taskRunRepository;
    this.taskService = taskService;
    this.mongoTemplate = mongoTemplate;
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

  public List<TaskRunEntity> retrieveTaskList(String wfRunId) {
    return taskRunRepository.findByWorkflowRunRef(wfRunId);
  }

  // TODO: determine a better way to handle the start and end task without saving them as a
  // TaskRunEntity
  public List<TaskRunEntity> createTaskList(
      WorkflowRevisionEntity wfRevisionEntity, WorkflowRunEntity wfRunEntity) {
    final List<TaskRunEntity> taskList = new LinkedList<>();
    // One fetch of the run's existing TaskRuns keyed by name - the (workflowRunRef, name) unique
    // index guarantees one per name, so no per-task find-first query is needed.
    final Map<String, TaskRunEntity> existingByName =
        taskRunRepository.findByWorkflowRunRef(wfRunEntity.getId()).stream()
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

          Task task = taskService.retrieveAndValidateTask(wfRevisionTask);
          taskRunEntity.setTaskRef(wfRevisionTask.getTaskRef());
          taskRunEntity.setTaskVersion(task.getVersion());
          LOGGER.debug(
              "[{}] Found Task: {} @ {}", wfRunEntity.getId(), task.getName(), task.getVersion());

          // Stack the labels based on label propagation
          // Task Template -> Workflow Task -> Run
          taskRunEntity.getLabels().putAll(task.getLabels());
          taskRunEntity.getLabels().putAll(wfRevisionTask.getLabels());
          taskRunEntity.getLabels().putAll(wfRunEntity.getLabels());

          // Add System Generated Annotations
          Map<String, Object> annotations = new HashMap<>();
          annotations.put("boomerang.io/generation", "4");
          annotations.put("boomerang.io/kind", "TaskRun");
          // Add Request Annotations from Workflow Service
          if (wfRunEntity.getAnnotations() != null
              && !wfRunEntity.getAnnotations().isEmpty()
              && wfRunEntity.getAnnotations().containsKey("boomerang.io/team-name")) {
            annotations.put(
                "boomerang.io/team-name",
                wfRunEntity.getAnnotations().get("boomerang.io/team-name"));
          }
          taskRunEntity.getAnnotations().putAll(annotations);

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
                task.getSpec().getParams().toString());
            LOGGER.debug(
                "[{}] Revision Task Params: {}",
                wfRunEntity.getId(),
                wfRevisionTask.getParams().toString());
            taskRunEntity.setParams(
                ParameterUtil.addUniqueParams(
                    ParameterUtil.abstractParamToRunParam(task.getSpec().getParams()),
                    wfRevisionTask.getParams()));
          } else {
            LOGGER.debug(
                "[{}] Task Template Params: {}",
                wfRunEntity.getId(),
                wfRevisionTask.getParams().toString());
            taskRunEntity.setParams(wfRevisionTask.getParams());
          }
          LOGGER.debug("[{}] Task Run Params: {}", wfRunEntity.getId(), taskRunEntity.getParams());
          // Timeout = the platform default (boomerang.io/task-timeout), or the task's own
          // value when set and not greater. 0 = unguarded.
          long timeout =
              wfRunEntity.getAnnotations() != null
                      && wfRunEntity.getAnnotations().get("boomerang.io/task-timeout") != null
                  ? Long.parseLong(
                      wfRunEntity.getAnnotations().get("boomerang.io/task-timeout").toString())
                  : 0L;
          if (wfRevisionTask.getTimeout() != null
              && wfRevisionTask.getTimeout() > 0
              && (timeout <= 0 || wfRevisionTask.getTimeout() < timeout)) {
            timeout = wfRevisionTask.getTimeout();
          }
          taskRunEntity.setTimeout(timeout);

          // Set TaskRun Spec from Task Spec - Debug and Deletion come from an alternate
          // source
          if (!Objects.isNull(task.getSpec().getImage()) && !task.getSpec().getImage().isEmpty()) {
            taskRunEntity.getSpec().setImage(task.getSpec().getImage());
          } else if (TaskType.template.equals(wfRevisionTask.getType())
              || TaskType.script.equals(wfRevisionTask.getType())) {
            taskRunEntity
                .getSpec()
                .setImage(
                    wfRunEntity.getAnnotations().get("boomerang.io/task-default-image").toString());
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
          TaskDeletion taskDeletion = TaskDeletion.Never;
          if (wfRunEntity.getAnnotations() != null
              && !wfRunEntity.getAnnotations().isEmpty()
              && wfRunEntity.getAnnotations().containsKey("boomerang.io/task-deletion")) {
            taskDeletion =
                TaskDeletion.getDeletion(
                    wfRunEntity.getAnnotations().get("boomerang.io/task-deletion").toString());
          }
          taskRunEntity.getSpec().setDeletion(taskDeletion);
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
    LOGGER.info("[{}] Task List: {}", wfRunEntity.getId(), taskList.toString());
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
            if (TaskType.decision.equals(currentTask.getType())) {
              String decisionValue = liveTask.getDecisionValue();
              processDecision(graph, tasks, decisionValue, currentTask.getId(), currentTask);
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
