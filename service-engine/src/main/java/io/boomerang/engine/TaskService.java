package io.boomerang.engine;

import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.enums.TaskStatus;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.engine.repository.TaskRepository;
import io.boomerang.engine.repository.TaskRevisionRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.stereotype.Service;

/*
 * Tasks are stored in a main TaskEntity with fields that have limited change scope
 * and a TaskRevisionEntity that holds the versioned elements
 *
 * It utilises a @DocumentReference for the parent field that allows us to retrieve the TaskEntity from within the TaskRevisionEntity when reading
 */
@Service
public class TaskService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final String CHANGELOG_INITIAL = "Initial Task Template";
  private static final String CHANGELOG_UPDATE = "Updated Task Template";
  private static final String NAME_REGEX = "^([0-9a-zA-Z\\-]+)$";
  private static final String ANNOTATION_GENERATION = "4";
  private static final String ANNOTATION_KIND = "Task";

  @Value("${flow.uniquenames.enabled}")
  private boolean uniqueNamesEnabled;

  private final TaskRepository taskRepository;
  private final TaskRevisionRepository taskRevisionRepository;
  private final MongoTemplate mongoTemplate;

  public TaskService(
      TaskRepository taskRepository,
      TaskRevisionRepository taskRevisionRepository,
      MongoTemplate mongoTemplate) {
    this.taskRepository = taskRepository;
    this.taskRevisionRepository = taskRevisionRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public Task get(String ref, Optional<Integer> version) {
    Optional<TaskEntity> taskEntity =
        uniqueNamesEnabled ? taskRepository.findByName(ref) : taskRepository.findById(ref);
    if (taskEntity.isPresent()) {
      Optional<TaskRevisionEntity> taskRevisionEntity;
      if (version.isPresent()) {
        taskRevisionEntity =
            taskRevisionRepository.findByParentRefAndVersion(
                taskEntity.get().getId(), version.get());
      } else {
        taskRevisionEntity =
            taskRevisionRepository.findByParentRefAndLatestVersion(taskEntity.get().getId());
      }
      if (taskRevisionEntity.isPresent()) {
        return convertEntityToModel(taskEntity.get(), taskRevisionEntity.get());
      }
    }
    throw new BoomerangException(
        BoomerangError.TASK_INVALID_REF, ref, version.isPresent() ? version.get() : "latest");
  }

  /*
   * Create Task
   *
   * TODO additional checks for mandatory fields
   */
  public Task create(Task request) {
    // Remove ID
    request.setId(null);

    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Unique Name Check
    if (uniqueNamesEnabled && taskRepository.existsByName(request.getName().toLowerCase())) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Set Display Name if not provided
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      request.setDisplayName(request.getName());
    }

    // Set System Generated Annotations
    request.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    request.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);

    // Set as initial version
    request.setVersion(1);
    ChangeLog changelog = new ChangeLog(CHANGELOG_INITIAL);
    updateChangeLog(request, changelog);
    request.setChangelog(changelog);

    // Save
    TaskEntity taskTemplateEntity = new TaskEntity(request);
    TaskRevisionEntity taskTemplateRevisionEntity = new TaskRevisionEntity(request);
    taskRepository.save(taskTemplateEntity);
    taskTemplateRevisionEntity.setParentRef(taskTemplateEntity.getId());
    taskRevisionRepository.save(taskTemplateRevisionEntity);

    return convertEntityToModel(taskTemplateEntity, taskTemplateRevisionEntity);
  }

  public Task apply(Task request, boolean replace) {
    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    if (!uniqueNamesEnabled && request.getId().isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, request.getName(), "latest");
    }

    // Does it already exist?
    Optional<TaskEntity> taskOpt =
        uniqueNamesEnabled
            ? taskRepository.findByName(request.getName())
            : taskRepository.findById(request.getId());
    if (taskOpt.isEmpty()) {
      return this.create(request);
    }
    TaskEntity taskEntity = taskOpt.get();

    // Check for active status
    if (TaskStatus.inactive.equals(taskEntity.getStatus())
        && !TaskStatus.active.equals(request.getStatus())) {
      throw new BoomerangException(
          BoomerangError.TASK_INACTIVE_STATUS, request.getName(), "latest");
    }

    // Get latest revision
    Optional<TaskRevisionEntity> taskRevisionEntity =
        taskRevisionRepository.findByParentRefAndLatestVersion(request.getId());
    if (taskRevisionEntity.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, request.getName(), "latest");
    }

    // Update TaskTemplateEntity
    // Set System Generated Annotations
    // Name (slug), Type, Creation Date, and Verified cannot be updated
    if (!request.getName().isBlank()) {
      taskEntity.setName(request.getName());
    }
    if (request.getStatus() != null) {
      taskEntity.setStatus(request.getStatus());
    }
    if (!request.getAnnotations().isEmpty()) {
      taskEntity.getAnnotations().putAll(request.getAnnotations());
    }
    taskEntity.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    taskEntity.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);
    if (!request.getLabels().isEmpty()) {
      taskEntity.getLabels().putAll(request.getLabels());
    }

    // Create / Replace TaskRevisionEntity
    TaskRevisionEntity newTaskRevisionEntity = new TaskRevisionEntity(request);
    if (replace) {
      newTaskRevisionEntity.setId(taskRevisionEntity.get().getId());
      newTaskRevisionEntity.setVersion(taskRevisionEntity.get().getVersion());
    } else {
      newTaskRevisionEntity.setVersion(taskRevisionEntity.get().getVersion() + 1);
    }
    // Set Display Name if not provided
    if (newTaskRevisionEntity.getDisplayName() == null
        || newTaskRevisionEntity.getDisplayName().isBlank()) {
      newTaskRevisionEntity.setDisplayName(request.getName());
    }

    // Update changelog
    ChangeLog changelog =
        new ChangeLog(
            taskRevisionEntity.get().getVersion().equals(1) ? CHANGELOG_INITIAL : CHANGELOG_UPDATE);
    updateChangeLog(request, changelog);
    newTaskRevisionEntity.setChangelog(changelog);

    // Save entities
    TaskEntity savedEntity = taskRepository.save(taskEntity);
    newTaskRevisionEntity.setParentRef(taskEntity.getId());
    TaskRevisionEntity savedRevision = taskRevisionRepository.save(newTaskRevisionEntity);
    return convertEntityToModel(savedEntity, savedRevision);
  }

  private void updateChangeLog(Task taskTemplate, ChangeLog changelog) {
    if (taskTemplate.getChangelog() != null) {
      if (taskTemplate.getChangelog().getAuthor() != null
          && !taskTemplate.getChangelog().getAuthor().isBlank()) {
        changelog.setAuthor(taskTemplate.getChangelog().getAuthor());
      }
      if (taskTemplate.getChangelog().getReason() != null
          && !taskTemplate.getChangelog().getReason().isBlank()) {
        changelog.setReason(taskTemplate.getChangelog().getReason());
      }
      if (taskTemplate.getChangelog().getDate() != null) {
        changelog.setDate(taskTemplate.getChangelog().getDate());
      }
    }
  }

  public Page<Task> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames,
      Optional<List<String>> queryIds) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
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
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(TaskStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryNames.isPresent()) {
      Criteria criteria = Criteria.where("name").in(queryNames.get());
      criteriaList.add(criteria);
    }

    if (queryIds.isPresent()) {
      List<ObjectId> queryOIds =
          queryIds.get().stream().map(ObjectId::new).collect(Collectors.toList());
      Criteria criteria = Criteria.where("_id").in(queryOIds);
      criteriaList.add(criteria);
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

    List<TaskEntity> taskEntities = mongoTemplate.find(query.with(pageable), TaskEntity.class);

    List<Task> tasks = new LinkedList<>();
    taskEntities.forEach(
        e -> {
          LOGGER.debug(e.toString());
          Optional<TaskRevisionEntity> taskRevisionEntity =
              taskRevisionRepository.findByParentRefAndLatestVersion(e.getId());
          if (taskRevisionEntity.isPresent()) {
            Task tt = convertEntityToModel(e, taskRevisionEntity.get());
            tasks.add(tt);
          }
        });

    Page<Task> pages = PageableExecutionUtils.getPage(tasks, pageable, () -> tasks.size());

    return pages;
  }

  /*
   * Retrieve all the changelogs and return by version
   */
  public List<ChangeLogVersion> changelog(String ref) {
    Task task = this.get(ref, Optional.empty());
    List<TaskRevisionEntity> taskRevisionEntities =
        taskRevisionRepository.findByParentRef(task.getId());
    if (taskRevisionEntities.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, ref, "latest");
    }
    List<ChangeLogVersion> changelogs = new LinkedList<>();
    taskRevisionEntities.forEach(
        v -> {
          ChangeLogVersion cl = new ChangeLogVersion();
          cl.setVersion(v.getVersion());
          if (v.getChangelog() != null) {
            cl.setAuthor(v.getChangelog().getAuthor());
            cl.setReason(v.getChangelog().getReason());
            cl.setDate(v.getChangelog().getDate());
          }
          changelogs.add(cl);
        });
    return changelogs;
  }

  public Task retrieveAndValidateTask(final WorkflowTask wfTask) {
    // Get TaskEntity - this will check valid ref and Version
    Task task = this.get(wfTask.getTaskRef(), Optional.ofNullable(wfTask.getTaskVersion()));

    // Check Task Status
    if (TaskStatus.inactive.equals(task.getStatus())) {
      throw new BoomerangException(
          BoomerangError.TASK_INACTIVE_STATUS, wfTask.getTaskRef(), wfTask.getTaskVersion());
    }
    return task;
  }

  public void delete(String name) {
    taskRevisionRepository.deleteByParentRef(name);
    taskRepository.deleteById(name);
  }

  private Task convertEntityToModel(TaskEntity entity, TaskRevisionEntity revision) {
    Task task = new Task();
    BeanUtils.copyProperties(entity, task);
    BeanUtils.copyProperties(revision, task, "id"); // want to keep the TaskEntity ID
    return task;
  }
}
