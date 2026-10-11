package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task and workflow changelogs list versions in ascending order whatever order the revisions were
 * stored in, so clients can read the last entry as the latest version.
 */
class ChangelogVersionOrderTest extends AbstractEngineIntegrationTest {

  private static final List<Integer> STORED_ORDER = List.of(3, 1, 2);

  @Autowired private TaskService taskService;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TaskRevisionRepository taskRevisionRepository;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @Test
  void taskChangelogListsVersionsAscending() {
    String taskId = taskRepository.save(new TaskEntity()).getId();
    STORED_ORDER.forEach(
        version -> {
          TaskRevisionEntity revision = new TaskRevisionEntity();
          revision.setParentRef(taskId);
          revision.setVersion(version);
          revision.setChangelog(new ChangeLog("version " + version));
          taskRevisionRepository.save(revision);
        });

    assertThat(taskService.changelog(taskId))
        .extracting(ChangeLogVersion::getVersion)
        .containsExactly(1, 2, 3);
  }

  @Test
  void workflowChangelogListsVersionsAscending() {
    String workflowId = workflowRepository.save(new WorkflowEntity()).getId();
    STORED_ORDER.forEach(
        version -> {
          WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
          revision.setWorkflowRef(workflowId);
          revision.setVersion(version);
          revision.setChangelog(new ChangeLog("version " + version));
          workflowRevisionRepository.save(revision);
        });

    assertThat(workflowService.changelog(workflowId).getBody())
        .extracting(ChangeLogVersion::getVersion)
        .containsExactly(1, 2, 3);
  }
}
