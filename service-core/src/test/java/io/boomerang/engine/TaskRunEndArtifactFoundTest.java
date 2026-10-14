package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.workflow.ArtifactService;
import java.util.List;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * The engine owns the download task's {@code found} result: a succeeded download ends with
 * found=true without the worker writing it, an end that already carries the result (the optional
 * miss ends with found=false) keeps it, and a failed download reports nothing.
 */
class TaskRunEndArtifactFoundTest extends AbstractEngineIntegrationTest {

  @Test
  void aSucceededDownloadReportsFoundTrue() {
    TaskRunEntity task = download("found-true");
    TaskRunEndRequest succeeded = new TaskRunEndRequest();
    succeeded.setStatus(RunStatus.succeeded);

    taskRunService.end(task.getId(), Optional.of(succeeded));

    assertThat(taskRunRepository.findById(task.getId()).orElseThrow().getResults())
        .extracting(RunResult::getName, RunResult::getValue)
        .containsExactly(Tuple.tuple(ArtifactService.FOUND_RESULT, "true"));
  }

  @Test
  void anEndThatAlreadyCarriesFoundKeepsIt() {
    TaskRunEntity task = download("found-false");
    TaskRunEndRequest notFound = new TaskRunEndRequest();
    notFound.setStatus(RunStatus.succeeded);
    notFound.setStatusReason("ArtifactNotFound");
    notFound.setResults(List.of(new RunResult(ArtifactService.FOUND_RESULT, "false")));

    taskRunService.end(task.getId(), Optional.of(notFound));

    TaskRunEntity ended = taskRunRepository.findById(task.getId()).orElseThrow();
    assertThat(ended.getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(ended.getResults())
        .extracting(RunResult::getName, RunResult::getValue)
        .containsExactly(Tuple.tuple(ArtifactService.FOUND_RESULT, "false"));
  }

  @Test
  void aFailedDownloadReportsNothing() {
    TaskRunEntity task = download("failed");
    TaskRunEndRequest failed = new TaskRunEndRequest();
    failed.setStatus(RunStatus.failed);

    taskRunService.end(task.getId(), Optional.of(failed));

    assertThat(taskRunRepository.findById(task.getId()).orElseThrow().getResults()).isEmpty();
  }

  private TaskRunEntity download(String name) {
    WorkflowRunEntity run = savedWorkflowRun("artifact-found-wf", RunStatus.running, RunPhase.running);
    return savedTaskRun(
        name,
        TaskType.downloadartifact,
        RunStatus.running,
        RunPhase.running,
        run.getWorkflowRef(),
        run.getId());
  }
}
