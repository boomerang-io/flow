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
 * An {@code ai} task runs the image and command its revision declares, like every other authored
 * type; the image must be under an allowed prefix and an {@code ai} task never runs a script.
 */
class TaskImageResolverTest {

  private static final String ARTIFACT_IMAGE = "boomerangio/task-flow:9.9.9";

  private final TaskImageResolver resolver = resolver();

  private static TaskImageResolver resolver() {
    TaskImageResolver resolver = new TaskImageResolver();
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
  void aiWithNoImageResolvesToNoneLikeAnyOtherType() {
    // No worker-image fallback: the dispatcher refuses such a task as NO_TASK_IMAGE.
    assertNull(resolver.image(task(TaskType.ai, new TaskRunSpec())));
    assertEquals("", resolver.image(task(TaskType.ai, spec("", List.of("prompt"), null))));
    assertNull(resolver.command(task(TaskType.ai, new TaskRunSpec())));
  }

  @Test
  void aiWithAnImageAndNoCommandLeavesTheImagesEntrypointInPlace() {
    TaskRun absent = task(TaskType.ai, spec("boomerangio/task-ai:2.0.0", null, null));

    assertNull(resolver.command(absent));
  }

  @Test
  void aiNeverRunsAScriptWhateverItsSpecSays() {
    TaskRun task =
        task(TaskType.ai, spec("boomerangio/task-ai:2.0.0", List.of("agent"), "#!/bin/sh\nid"));

    assertNull(resolver.script(task));
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

  private static final String RUN_ID = "6ad1bfa3cfefa66e88d5e24a";
  private static final String TASK_RUN_ID = "6ad1bfa6cfefa66e88d5e2e0";
  private static final String TRACEPARENT = "00-000000006ad1bfa3cfefa66e88d5e24a-cfefa66e88d5e2e0-01";

  private static TaskRun tracedTask(TaskType type, List<TaskEnvVar> envs) {
    TaskRunSpec spec = spec("boomerangio/task-ai:2.0.0", List.of("prompt"), null);
    spec.setEnvs(envs);
    TaskRun task = task(type, spec);
    task.setId(TASK_RUN_ID);
    task.setWorkflowRunRef(RUN_ID);
    task.setTaskRef("ai-prompt");
    task.setName("summarise, then=rank");
    return task;
  }

  private static TaskImageResolver exporting() {
    TaskImageResolver exporting = resolver();
    ReflectionTestUtils.setField(exporting, "aiOtelEndpoint", "https://cloud.langfuse.com/api/public/otel");
    ReflectionTestUtils.setField(exporting, "aiOtelHeaders", "Authorization=Basic abc");
    ReflectionTestUtils.setField(exporting, "aiOtelProtocol", "http/protobuf");
    return exporting;
  }

  private static String value(List<TaskEnvVar> environment, String name) {
    return environment.stream()
        .filter(var -> name.equals(var.getName()))
        .map(TaskEnvVar::getValue)
        .reduce((first, last) -> last)
        .orElse(null);
  }

  @Test
  void everyTaskGetsATraceparentDerivedFromItsRunAndTaskRun() {
    assertEquals(TRACEPARENT, value(resolver.environment(tracedTask(TaskType.ai, null)), "TRACEPARENT"));
    assertEquals(
        TRACEPARENT, value(resolver.environment(tracedTask(TaskType.custom, null)), "TRACEPARENT"));
  }

  @Test
  void anAiPodGetsTheExporterSettingsWhenAnEndpointIsConfigured() {
    List<TaskEnvVar> environment = exporting().environment(tracedTask(TaskType.ai, null));

    assertEquals("https://cloud.langfuse.com/api/public/otel", value(environment, "OTEL_EXPORTER_OTLP_ENDPOINT"));
    assertEquals("Authorization=Basic abc", value(environment, "OTEL_EXPORTER_OTLP_HEADERS"));
    assertEquals("http/protobuf", value(environment, "OTEL_EXPORTER_OTLP_PROTOCOL"));
    assertEquals("flow-task-ai-prompt", value(environment, "OTEL_SERVICE_NAME"));
    assertEquals(
        "flow.workflow.run.id=" + RUN_ID
            + ",flow.task.run.id=" + TASK_RUN_ID
            + ",flow.task.name=summarise%2C%20then%3Drank",
        value(environment, "OTEL_RESOURCE_ATTRIBUTES"));
  }

  @Test
  void noExporterSettingsWithoutAnEndpointOrOnANonAiPod() {
    List<TaskEnvVar> blank = resolver.environment(tracedTask(TaskType.ai, null));
    List<TaskEnvVar> custom = exporting().environment(tracedTask(TaskType.custom, null));

    for (String name :
        List.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_HEADERS",
            "OTEL_EXPORTER_OTLP_PROTOCOL",
            "OTEL_SERVICE_NAME",
            "OTEL_RESOURCE_ATTRIBUTES")) {
      assertNull(value(blank, name), name);
      assertNull(value(custom, name), name);
    }
  }

  @Test
  void aTasksOwnOtelVariableWinsOverTheDefault() {
    List<TaskEnvVar> environment =
        exporting()
            .environment(
                tracedTask(TaskType.ai, List.of(new TaskEnvVar("OTEL_SERVICE_NAME", "my-agent"))));

    // Later entries win when the executor builds the container's environment by name.
    assertEquals("my-agent", value(environment, "OTEL_SERVICE_NAME"));
  }
}
