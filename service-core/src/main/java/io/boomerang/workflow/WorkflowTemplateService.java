package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowTemplateEntity;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowTemplate;
import io.boomerang.common.util.PagingUtil;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.workflow.repository.WorkflowTemplateRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Read access to the Workflow Templates. Templates are content: the migrations seed them and a v3
 * upgrade imports them, so this service only reads. A Workflow is created from a template by
 * posting the template body to the workspace Workflow create route.
 */
@Service
public class WorkflowTemplateService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowTemplateRepository wfTemplateRepository;
  private final MongoTemplate mongoTemplate;

  public WorkflowTemplateService(
      WorkflowTemplateRepository wfTemplateRepository, MongoTemplate mongoTemplate) {
    this.wfTemplateRepository = wfTemplateRepository;
    this.mongoTemplate = mongoTemplate;
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
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    Query query =
        new Query(new QueryCriteria().labels(queryLabels).in("name", queryNames).build());
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);

    List<WorkflowTemplateEntity> wfTemplateEntities =
        mongoTemplate.find(query, WorkflowTemplateEntity.class);

    List<WorkflowTemplate> wfTemplates = new LinkedList<>();
    wfTemplateEntities.forEach(e -> wfTemplates.add(new WorkflowTemplate(e)));

    Page<WorkflowTemplate> pages =
        PagingUtil.page(wfTemplates, pageable, mongoTemplate, query, WorkflowTemplateEntity.class);

    return pages;
  }
}
