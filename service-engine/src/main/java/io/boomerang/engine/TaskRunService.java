package io.boomerang.engine;

import io.boomerang.client.LogClient;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.util.ParameterUtil;
import io.boomerang.util.ResultUtil;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/*
 * Handles CRUD of TaskRuns
 */
@Service
public class TaskRunService {
  private static final Logger LOGGER = LogManager.getLogger();

  private final TaskExecutionClient taskExecutionClient;
  private final TaskExecutionService taskExecutionService;
  private final LogClient logClient;
  private final TaskRunRepository taskRunRepository;
  private final MongoTemplate mongoTemplate;

  public TaskRunService(
      @Lazy TaskExecutionClient taskExecutionClient,
      @Lazy TaskExecutionService taskExecutionService,
      @Lazy LogClient logClient,
      TaskRunRepository taskRunRepository,
      MongoTemplate mongoTemplate) {
    this.taskExecutionClient = taskExecutionClient;
    this.taskExecutionService = taskExecutionService;
    this.logClient = logClient;
    this.taskRunRepository = taskRunRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public ResponseEntity<TaskRun> get(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRun taskRun = new TaskRun(optTaskRunEntity.get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public Page<TaskRun> query(
      Optional<Date> from,
      Optional<Date> to,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO: centralize the checks in a common filter class
    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryStatus.isPresent()) {
      if (queryStatus.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(RunStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryPhase.isPresent()) {
      if (queryPhase.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(RunPhase.class, q))) {
        Criteria criteria = Criteria.where("phase").in(queryPhase.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "phase");
      }
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    List<TaskRunEntity> taskRunEntities = mongoTemplate.find(query, TaskRunEntity.class);

    List<TaskRun> taskRuns = new LinkedList<>();
    taskRunEntities.forEach(e -> taskRuns.add(new TaskRun(e)));

    Page<TaskRun> pages = PageableExecutionUtils.getPage(taskRuns, pageable, () -> taskRuns.size());

    return pages;
  }

  public ResponseEntity<TaskRun> start(
      String taskRunId, Optional<TaskRunStartRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          taskRunEntity.setParams(
              ParameterUtil.addUniqueParams(
                  taskRunEntity.getParams(), optRunRequest.get().getParams()));
          if (!Objects.isNull(optRunRequest.get().getTimeout())
              && optRunRequest.get().getTimeout() != 0) {
            taskRunEntity.setTimeout(optRunRequest.get().getTimeout());
          }
        }
        taskExecutionClient.start(taskExecutionService, taskRunEntity);
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> end(String taskRunId, Optional<TaskRunEndRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          if (optRunRequest.get().getStatusMessage() != null
              && !optRunRequest.get().getStatusMessage().isEmpty()) {
            taskRunEntity.setStatusMessage(optRunRequest.get().getStatusMessage());
          }
          taskRunEntity.setResults(
              ResultUtil.addUniqueResults(
                  taskRunEntity.getResults(), optRunRequest.get().getResults()));
          if (optRunRequest.get().getStatus() == null) {
            taskRunEntity.setStatus(RunStatus.succeeded);
          } else if (!(RunStatus.failed.equals(optRunRequest.get().getStatus())
              || RunStatus.succeeded.equals(optRunRequest.get().getStatus())
              || RunStatus.invalid.equals(optRunRequest.get().getStatus()))) {
            throw new BoomerangException(BoomerangError.TASKRUN_INVALID_END_STATUS);
          } else {
            taskRunEntity.setStatus(optRunRequest.get().getStatus());
          }
        }
        taskExecutionClient.end(taskExecutionService, taskRunEntity);
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> cancel(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        taskRunEntity.setStatus(RunStatus.cancelled);
        taskExecutionClient.end(taskExecutionService, taskRunEntity);
        TaskRun taskRun = new TaskRun(optTaskRunEntity.get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public StreamingResponseBody streamLog(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);

      // TODO sanitise and remove secure parameters
      //    List<String> removeList = buildRemovalList(taskId, taskExecution, activity);
      //    LOGGER.debug("Removal List Count: {} ", removeList.size());
      if (optTaskRunEntity.isPresent()) {
        return logClient.streamLog(
            optTaskRunEntity.get().getWorkflowRef(),
            optTaskRunEntity.get().getWorkflowRunRef(),
            optTaskRunEntity.get().getId());
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }
}
