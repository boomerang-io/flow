package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

/**
 * framework-review-proposals.md A10: {@code WorkflowRunService.query}'s {@code getPage} supplier
 * used to be {@code () -> wfRuns.size()} - the already-paged list - so a full page always reported
 * {@code totalElements == limit} regardless of how many rows actually matched. The fix counts the
 * SAME query with {@code skip}/{@code limit} reset ({@code Query.of(query).skip(-1).limit(-1)}),
 * mirroring {@code MongoQueryExecution.PagedExecution}. This pins the corrected total across more
 * than one page of results - the exact condition the bug could not report correctly.
 */
class WorkflowRunQueryPagedTotalElementsTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;

  @Test
  void totalElementsReflectsAllMatchingRunsNotJustThePage() {
    String workflowRef = "wfrun-total-elements-test-" + UUID.randomUUID();
    for (int i = 0; i < 25; i++) {
      savedWorkflowRun(workflowRef, RunStatus.succeeded, RunPhase.completed);
    }

    Page<WorkflowRun> page =
        workflowRunService.query(
            Optional.empty(),
            Optional.empty(),
            Optional.of(10),
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of(workflowRef)),
            Optional.empty());

    assertEquals(25, page.getTotalElements(), "totalElements must count all 25 matching runs");
    assertEquals(3, page.getTotalPages(), "25 rows at limit=10 must report 3 pages");
    assertEquals(10, page.getContent().size(), "the first page must still hold only 10 rows");
  }
}
