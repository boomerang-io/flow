package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.dispatcher.sdk.model.TaskEnvVar;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * An {@code ai} task runs the image and command its revision declares; one that declares no image
 * runs the dispatcher's configured worker with the {@code prompt} command. Every other type keeps
 * taking its runtime from its own spec.
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
    // The property's default prefix, as Spring would bind it.
    ReflectionTestUtils.setField(resolver, "aiImagePrefixes", List.of("boomerangio/task-ai"));
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
  void aiWithNoImageRunsTheConfiguredWorkerImageWithThePromptCommand() {
    // The spec the seeded `ai` TaskRun carries: no image, no command, no script.
    TaskRun task = task(TaskType.ai, new TaskRunSpec());

    assertEquals(AI_IMAGE, resolver.image(task));
    assertEquals(List.of("prompt"), resolver.command(task));
    assertNull(resolver.arguments(task));
    assertNull(resolver.script(task));
  }

  @Test
  void aiWithABlankImageIsTheSameAsNoImage() {
    TaskRunSpec spec = spec("  ", List.of("agent"), null);
    spec.setArguments(List.of("--verbose"));
    TaskRun task = task(TaskType.ai, spec);

    assertEquals(AI_IMAGE, resolver.image(task));
    assertEquals(List.of("prompt"), resolver.command(task));
    assertNull(resolver.arguments(task));
  }

  @Test
  void aiRunsTheImageAndCommandItsRevisionDeclares() {
    TaskRunSpec spec = spec("boomerangio/task-ai:2.0.0", List.of("agent"), null);
    spec.setArguments(List.of("--max-turns", "10"));
    TaskRun task = task(TaskType.ai, spec);

    assertEquals("boomerangio/task-ai:2.0.0", resolver.image(task));
    assertEquals(List.of("agent"), resolver.command(task));
    assertEquals(List.of("--max-turns", "10"), resolver.arguments(task));
    assertNull(resolver.script(task));
  }

  @Test
  void aiWithAnImageAndNoCommandLeavesTheImagesEntrypointInPlace() {
    TaskRun empty = task(TaskType.ai, spec("boomerangio/task-ai:2.0.0", List.of(), null));
    TaskRun absent = task(TaskType.ai, spec("boomerangio/task-ai:2.0.0", null, null));

    assertNull(resolver.command(empty));
    assertNull(resolver.command(absent));
  }

  @Test
  void aiGetsTheConfiguredCredentialDefaultsAheadOfItsOwnEnvironment() {
    TaskImageResolver configured = resolver();
    ReflectionTestUtils.setField(configured, "aiEndpoint", "https://openrouter.ai/api/v1");
    ReflectionTestUtils.setField(configured, "aiToken", "sk-or-test");
    TaskRunSpec spec = new TaskRunSpec();
    spec.setEnvs(List.of(new TaskEnvVar("LOG_LEVEL", "debug")));

    assertEquals(
        List.of(
            new TaskEnvVar("OPENAI_BASE_URL", "https://openrouter.ai/api/v1"),
            new TaskEnvVar("OPENAI_API_KEY", "sk-or-test"),
            new TaskEnvVar("LOG_LEVEL", "debug")),
        configured.environment(task(TaskType.ai, spec)));
    // Every other type gets only what its spec defines.
    assertEquals(
        List.of(new TaskEnvVar("LOG_LEVEL", "debug")),
        configured.environment(task(TaskType.custom, spec)));
  }

  @Test
  void blankCredentialDefaultsInjectNothing() {
    // The resolver under test carries the properties' defaults: both blank.
    assertEquals(List.of(), resolver.environment(task(TaskType.ai, new TaskRunSpec())));
    assertEquals(List.of(), resolver.environment(task(TaskType.ai, null)));
  }

  @Test
  void anAiImageIsAllowedOnlyUnderAConfiguredPrefix() {
    // The resolver under test carries the property's default prefix, boomerangio/task-ai.
    assertTrue(resolver.allowedAiImage("boomerangio/task-ai:2.0.0"));
    assertTrue(resolver.allowedAiImage("boomerangio/task-ai@sha256:0123abcd"));
    assertFalse(resolver.allowedAiImage("evil:latest"));
    assertFalse(resolver.allowedAiImage("boomerangio/task-flow:3.2.1"));
    assertFalse(resolver.allowedAiImage(""));
    assertFalse(resolver.allowedAiImage(null));
  }

  @Test
  void anOperatorsOwnPrefixAdmitsItsImages() {
    TaskImageResolver configured = resolver();
    ReflectionTestUtils.setField(
        configured, "aiImagePrefixes", List.of("boomerangio/task-ai", " registry.example.com/ai/ "));

    assertTrue(configured.allowedAiImage("registry.example.com/ai/agent:1.0"));
    assertTrue(configured.allowedAiImage("boomerangio/task-ai:2.0.0"));
    assertFalse(configured.allowedAiImage("registry.example.com/other:1.0"));
  }

  @Test
  void aiNeverRunsAScriptWhateverItsSpecSays() {
    TaskRun task =
        task(TaskType.ai, spec("boomerangio/task-ai:2.0.0", List.of("agent"), "#!/bin/sh\nid"));

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
