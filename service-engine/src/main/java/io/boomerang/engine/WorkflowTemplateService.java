package io.boomerang.engine;

import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.WorkflowTemplateEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTemplate;
import io.boomerang.engine.repository.TaskRevisionRepository;
import io.boomerang.engine.repository.WorkflowTemplateRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * This service implements the CRUD operations on a WorkflowTemplate
 */
@Service
public class WorkflowTemplateService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String CHANGELOG_INITIAL = "Initial WorkflowTemplate";
  private static final String CHANGELOG_UPDATE = "Updated WorkflowTemplate";
  private static final String NAME_REGEX = "^([0-9a-zA-Z\\\\-]+)$";

  private final WorkflowTemplateRepository wfTemplateRepository;
  private final MongoTemplate mongoTemplate;
  private final TaskService taskService;
  private final TaskRevisionRepository taskRevisionRepository;

  public WorkflowTemplateService(
      WorkflowTemplateRepository wfTemplateRepository,
      MongoTemplate mongoTemplate,
      TaskService taskService,
      TaskRevisionRepository taskRevisionRepository) {
    this.wfTemplateRepository = wfTemplateRepository;
    this.mongoTemplate = mongoTemplate;
    this.taskService = taskService;
    this.taskRevisionRepository = taskRevisionRepository;
  }

  /*
   * Get WorklfowTemplate
   */
  public WorkflowTemplate get(String name, Optional<Integer> version, boolean withTasks) {
    Optional<WorkflowTemplateEntity> wfTemplateEntity;
    if (version.isEmpty()) {
      wfTemplateEntity = wfTemplateRepository.findByNameAndLatestVersion(name);
      if (wfTemplateEntity.isEmpty()) {
        // TODO change to correct error
        throw new BoomerangException(BoomerangError.TASK_INVALID_REF, name, "latest");
      }
    } else {
      wfTemplateEntity = wfTemplateRepository.findByNameAndVersion(name, version.get());
      if (wfTemplateEntity.isEmpty()) {
        // TODO change to correct error
        throw new BoomerangException(BoomerangError.TASK_INVALID_REF, name, version.get());
      }
    }
    return new WorkflowTemplate(wfTemplateEntity.get());
  }

  /*
   * Query for Workflow Templates.
   */
  public Page<WorkflowTemplate> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryNames) {
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

    if (queryNames.isPresent()) {
      Criteria criteria = Criteria.where("name").in(queryNames.get());
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

    List<WorkflowTemplateEntity> wfTemplateEntities =
        mongoTemplate.find(query.with(pageable), WorkflowTemplateEntity.class);

    List<WorkflowTemplate> wfTemplates = new LinkedList<>();
    wfTemplateEntities.forEach(e -> wfTemplates.add(new WorkflowTemplate(e)));

    Page<WorkflowTemplate> pages =
        PageableExecutionUtils.getPage(wfTemplates, pageable, () -> wfTemplates.size());

    return pages;
  }

  /*
   * Create Workflow Template.
   */
  public WorkflowTemplate create(WorkflowTemplate request) {
    // Ensure name is provided
    if (request.getName() == null || request.getName().isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      // TODO change the error
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Unique Name Check
    if (wfTemplateRepository
        .findByNameAndLatestVersion(request.getName().toLowerCase())
        .isPresent()) {
      // TODO change the error
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }
    WorkflowTemplateEntity wfTemplateEntity = new WorkflowTemplateEntity();
    wfTemplateEntity.setName(request.getName());
    // Set Display Name if not provided
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      wfTemplateEntity.setDisplayName(request.getName());
    }
    wfTemplateEntity.setIcon(request.getIcon());
    wfTemplateEntity.setDescription(request.getDescription());
    wfTemplateEntity.setLabels(request.getLabels());
    wfTemplateEntity.setAnnotations(request.getAnnotations());
    wfTemplateEntity.setVersion(1);
    ChangeLog changelog = new ChangeLog(CHANGELOG_INITIAL);
    if (request.getChangelog() != null) {
      if (request.getChangelog().getAuthor() != null) {
        changelog.setAuthor(request.getChangelog().getAuthor());
      }
      if (request.getChangelog().getReason() != null) {
        changelog.setReason(request.getChangelog().getReason());
      }
      if (request.getChangelog().getDate() != null) {
        changelog.setDate(request.getChangelog().getDate());
      }
    }
    wfTemplateEntity.setChangelog(changelog);
    wfTemplateEntity.setMarkdown(request.getMarkdown());
    wfTemplateEntity.setParams(request.getParams());
    wfTemplateEntity.setWorkspaces(request.getWorkspaces());
    wfTemplateEntity.setTasks(request.getTasks());
    wfTemplateEntity.setTimeout(request.getTimeout());
    wfTemplateEntity.setRetries(request.getRetries());

    // Check Task Names are unique
    List<String> filteredNames =
        wfTemplateEntity.getTasks().stream().map(t -> t.getName()).collect(Collectors.toList());
    List<String> uniqueFilteredNames =
        filteredNames.stream().distinct().collect(Collectors.toList());
    LOGGER.debug("Name sizes: {} -> {}", filteredNames, uniqueFilteredNames);
    if (filteredNames.size() != uniqueFilteredNames.size()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_NON_UNIQUE_TASK_NAME);
    }

    // Check Task Template references are valid
    for (final WorkflowTask wfTemplateTask : wfTemplateEntity.getTasks()) {
      if (!TaskType.start.equals(wfTemplateTask.getType())
          && !TaskType.end.equals(wfTemplateTask.getType())) {

        // Shared utility with DAGUtility
        taskService.retrieveAndValidateTask(wfTemplateTask);
      }
    }

    return new WorkflowTemplate(wfTemplateEntity);
  }

  /*
   * Apply allows you to create a new version or override an existing WorkflowTemplate as well as create new
   * WorkflowTemplate with supplied ID
   */
  public WorkflowTemplate apply(WorkflowTemplate request, boolean replace) {
    // Ensure name is provided
    if (request.getName() == null || request.getName().isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      // TODO change the error
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Does it already exist?
    Optional<WorkflowTemplateEntity> optWfTemplateEntity =
        wfTemplateRepository.findByNameAndLatestVersion(request.getName().toLowerCase());
    if (!optWfTemplateEntity.isPresent()) {
      return this.create(request);
    }

    WorkflowTemplateEntity wfTemplateEntity = optWfTemplateEntity.get();
    // Override Id & version
    if (!replace) {
      wfTemplateEntity.setId(null);
      wfTemplateEntity.setVersion(optWfTemplateEntity.get().getVersion() + 1);
    }
    if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
      wfTemplateEntity.setDisplayName(request.getDisplayName());
    }
    wfTemplateEntity.setCreationDate(new Date());
    ChangeLog changelog = new ChangeLog(CHANGELOG_UPDATE);
    if (wfTemplateEntity.getChangelog() != null) {
      if (wfTemplateEntity.getChangelog().getAuthor() != null) {
        changelog.setAuthor(wfTemplateEntity.getChangelog().getAuthor());
      }
      if (wfTemplateEntity.getChangelog().getReason() != null) {
        changelog.setReason(wfTemplateEntity.getChangelog().getReason());
      }
      if (wfTemplateEntity.getChangelog().getDate() != null) {
        changelog.setDate(wfTemplateEntity.getChangelog().getDate());
      }
    }
    wfTemplateEntity.setChangelog(changelog);
    if (request.getDescription() != null && !request.getDescription().isBlank()) {
      wfTemplateEntity.setDescription(request.getDescription());
    }
    if (request.getLabels() != null && !request.getLabels().isEmpty()) {
      if (replace) {
        wfTemplateEntity.setLabels(request.getLabels());
      } else {
        wfTemplateEntity.getLabels().putAll(request.getLabels());
      }
    }
    if (request.getAnnotations() != null && !request.getAnnotations().isEmpty()) {
      if (replace) {
        wfTemplateEntity.setAnnotations(request.getAnnotations());
      } else {
        wfTemplateEntity.getAnnotations().putAll(request.getAnnotations());
      }
    }
    if (request.getIcon() != null) {
      wfTemplateEntity.setIcon(request.getIcon());
    }
    if (request.getMarkdown() != null && !request.getMarkdown().isEmpty()) {
      wfTemplateEntity.setMarkdown(request.getMarkdown());
    }
    if (request.getParams() != null && !request.getParams().isEmpty()) {
      wfTemplateEntity.setParams(request.getParams());
    }
    if (request.getTasks() != null) {
      wfTemplateEntity.setTasks(request.getTasks());
    }
    if (request.getTimeout() != null) {
      wfTemplateEntity.setTimeout(request.getTimeout());
    }
    if (request.getRetries() != null) {
      wfTemplateEntity.setRetries(request.getRetries());
    }
    WorkflowTemplateEntity savedEntity = wfTemplateRepository.save(wfTemplateEntity);
    //
    // appliedWorkflow.setTasks(TaskMapper.workflowTasksToListOfTasks(newWorkflowRevisionEntity.getTasks()));
    WorkflowTemplate template = new WorkflowTemplate(savedEntity);
    template.setUpgradesAvailable(areTaskUpgradesAvailable(savedEntity));
    return template;
  }

  /*
   * Delete WorkflowTemplate
   */
  public void delete(String name) {
    // Ensure name is provided
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Does it exist?
    Optional<WorkflowTemplateEntity> optWfTemplateEntity =
        wfTemplateRepository.findByNameAndLatestVersion(name.toLowerCase());

    if (optWfTemplateEntity.isPresent()) {
      wfTemplateRepository.deleteAllByName(name);
    }
  }

  private boolean areTaskUpgradesAvailable(WorkflowTemplateEntity entity) {
    for (WorkflowTask t : entity.getTasks()) {
      Optional<TaskRevisionEntity> task =
          taskRevisionRepository.findByParentRefAndLatestVersion(t.getTaskRef());
      if (task.isPresent()) {
        if (t.getTaskVersion() != null && (t.getTaskVersion() < task.get().getVersion())) {
          return true;
        }
      }
    }
    return false;
  }
}
