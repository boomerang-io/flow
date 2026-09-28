package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * An {@code ai} task's author never builds a container, so the dispatcher - not the definition -
 * decides what runs. Every other type keeps taking its runtime from its own spec.
 */
class TaskImageResolverTest {

  // The worker image is released from boomerang-io/tasks on its own version line, not the product
  // tag, so the pinned tag under test is deliberately not a 5.x product version.
  private static final String AI_IMAGE = "boomerangio/task-ai:1.2.3";
  private static final String ARTIFACT_IMAGE = "boomerangio/task-flow:9.9.9";

  private final TaskImageResolver resolver = resolver();

  private static TaskImageResolver resolver() {
    TaskImageResolver resolver = new TaskImageResolver();
    ReflectionTestUtils.setField(resolver, "aiImage", AI_IMAGE);
    ReflectionTestUtils.setField(resolver, "artifactImage", ARTIFACT_IMAGE);
    return resolver;
  }

  private static TaskRun task(TaskType type, TaskRunSpec spec) {
    TaskRun task = new TaskRun();
    task.setId("task-1");
    task.setType(type);
    task.setSpec(spec);
    return task;
  }

  private static TaskRunSpec spec(String image, List<String> command, String script) {
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage(image);
    spec.setCommand(command);
    spec.setScript(script);
    return spec;
  }

  @Test
  void customTakesImageAndCommandFromItsOwnSpec() {
    TaskRun task = task(TaskType.custom, spec("alpine:3.19", List.of("echo", "hello"), null));

    assertEquals("alpine:3.19", resolver.image(task));
    assertEquals(List.of("echo", "hello"), resolver.command(task));
    assertNull(resolver.script(task));
  }

  @Test
  void scriptKeepsItsScriptBody() {
    TaskRun task = task(TaskType.script, spec("alpine:3.19", null, "#!/bin/sh\necho hello"));

    assertEquals("alpine:3.19", resolver.image(task));
    assertEquals("#!/bin/sh\necho hello", resolver.script(task));
  }

  @Test
  void aiRunsTheConfiguredWorkerImageWithThePromptCommand() {
    // The spec an `ai` TaskRun actually carries: no image, no command, no script.
    TaskRun task = task(TaskType.ai, new TaskRunSpec());

    assertEquals(AI_IMAGE, resolver.image(task));
    assertEquals(List.of("prompt"), resolver.command(task));
    assertNull(resolver.script(task));
  }

  @Test
  void aiIgnoresAnImageCommandOrScriptThatReachedItsSpec() {
    // A definition must not be able to point the AI worker at another container.
    TaskRun task = task(TaskType.ai, spec("evil:latest", List.of("sh", "-c", "id"), "#!/bin/sh\nid"));

    assertEquals(AI_IMAGE, resolver.image(task));
    assertEquals(List.of("prompt"), resolver.command(task));
    assertNull(resolver.script(task));
  }

  @Test
  void aTaskRunWithNoSpecResolvesToNothingRatherThanFailing() {
    TaskRun task = task(TaskType.custom, null);

    assertNull(resolver.image(task));
    assertNull(resolver.command(task));
    assertNull(resolver.script(task));
  }

  @Test
  void artifactTasksRunTheDefaultWorkersArtifactCommandsWhateverTheirSpecSays() {
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("attacker/image:latest");
    spec.setCommand(List.of("sh"));
    spec.setArguments(List.of("-c", "evil"));
    spec.setScript("evil");

    TaskRun upload = task(TaskType.uploadartifact, spec);
    TaskRun download = task(TaskType.downloadartifact, spec);

    assertEquals(ARTIFACT_IMAGE, resolver.image(upload));
    assertNull(resolver.command(upload));
    assertNull(resolver.script(upload));
    assertEquals(
        List.of("artifact", "upload"), resolver.arguments(upload));
    assertEquals(
        List.of("artifact", "download"), resolver.arguments(download));
  }

  @Test
  void aTemplateTaskKeepsItsAuthoredArguments() {
    TaskRunSpec spec = new TaskRunSpec();
    spec.setArguments(List.of("file", "createFile"));

    assertEquals(
        List.of("file", "createFile"), resolver.arguments(task(TaskType.template, spec)));
  }
}
