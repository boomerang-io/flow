package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedList;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full engine Spring context against a single static Testcontainers MongoDB. All
 * subclasses share one cached context and one database: tests must create their own data and
 * assert on their own ids, never on global collection state. Externals are neutralised (JobRunr
 * background server/dashboard off, no CloudEvents egress, no audit) so nothing else needs to run.
 */
@SpringBootTest
public abstract class AbstractEngineIntegrationTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    MONGO.start();
  }

  @DynamicPropertySource
  static void engineTestProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("boomerang"));
    registry.add("flow.mongo.collection.prefix", () -> "flowtest");
    // Keep the JobRunr JobScheduler bean (required at boot) without running background jobs.
    registry.add("org.jobrunr.background-job-server.enabled", () -> "false");
    registry.add("org.jobrunr.dashboard.enabled", () -> "false");
    registry.add("flow.events.sink.enabled", () -> "false");
    registry.add("flow.audit.enabled", () -> "false");
    // Watcher sweeps are exercised deterministically by direct invocation, not on a schedule.
    registry.add("flow.watcher.enabled", () -> "false");
  }

  @Autowired protected TaskRunRepository taskRunRepository;
  @Autowired protected WorkflowRunRepository workflowRunRepository;

  protected static ConditionFactory awaitEngine(String alias) {
    return Awaitility.await(alias)
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(250));
  }

  protected WorkflowRunEntity savedWorkflowRun(
      String workflowRef, RunStatus status, RunPhase phase) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(workflowRef);
    run.setStatus(status);
    run.setPhase(phase);
    run.setCreationDate(new Date());
    run.setStartTime(new Date());
    return workflowRunRepository.save(run);
  }

  protected TaskRunEntity savedTaskRun(
      String name,
      TaskType type,
      RunStatus status,
      RunPhase phase,
      String workflowRef,
      String workflowRunRef) {
    TaskRunEntity run = new TaskRunEntity();
    run.setName(name);
    run.setType(type);
    run.setStatus(status);
    run.setPhase(phase);
    run.setCreationDate(new Date());
    run.setWorkflowRef(workflowRef);
    run.setWorkflowRunRef(workflowRunRef);
    run.setDependencies(new LinkedList<>());
    return taskRunRepository.save(run);
  }
}
