package io.boomerang.workflow.tekton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import io.boomerang.common.model.ParamSpec;
import io.boomerang.common.model.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/*
 * Uplifted from the v3-era TektonImportExportTests — validates Tekton Task YAML deserialisation
 * into the TektonTask model and conversion to/from the Flow Task model.
 */
class TektonConverterTests {

  @Test
  void testYamlImport() throws IOException {
    TektonTask task = loadTektonTask("yaml/import.yaml");
    assertEquals("tekton.dev/v1beta1", task.getApiVersion());
    assertEquals("Task", task.getKind());

    Metadata metadata = task.getMetadata();
    assertEquals("example-task-name", metadata.getName());
    assertEquals("value", metadata.getLabels().get("key"));

    Map<String, Object> annotations = metadata.getAnnotations();
    assertEquals(5, annotations.size());
    assertEquals("fix", annotations.get("boomerang.io/icon"));
    assertEquals("Worker", annotations.get("boomerang.io/category"));
    assertEquals(1, annotations.get("boomerang.io/revision"));
    assertEquals("cool task", annotations.get("description"));

    Spec spec = task.getSpec();
    List<ParamSpec> params = spec.getParams();
    assertEquals(1, params.size());
    ParamSpec param = params.get(0);
    assertEquals("pathToDockerFile", param.getName());
    assertEquals("string", param.getType().toString());
    assertEquals("The path to the dockerfile to build", param.getDescription());

    List<Step> steps = spec.getSteps();
    assertEquals(1, steps.size());
    Step step = steps.get(0);
    assertEquals("ubuntu", step.getImage());
    assertEquals("ubuntu-example", step.getName());
    assertEquals("entrypoint", step.getCommand().get(0));
    assertEquals("ubuntu-build-example", step.getArgs().get(0));
    assertEquals("SECRETS-example.md", step.getArgs().get(1));
  }

  @Test
  void testYamlImportWithEmptyLabels() throws IOException {
    TektonTask task = loadTektonTask("yaml/import2.yaml");
    assertEquals("tekton.dev/v1beta1", task.getApiVersion());
    assertEquals("Task", task.getKind());
  }

  @Test
  void testTektonToTaskConversion() throws IOException {
    TektonTask tektonTask = loadTektonTask("yaml/import.yaml");
    Task task = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    assertEquals("example-task-name", task.getName());
    assertEquals("Worker", task.getCategory());
  }

  @Test
  void testTaskToTektonConversion() throws IOException {
    TektonTask tektonTask = loadTektonTask("yaml/import.yaml");
    Task task = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);

    TektonTask exported = TektonConverter.convertTaskTemplateToTektonTask(task);
    assertEquals("tekton.dev/v1beta1", exported.getApiVersion());
    assertEquals("Task", exported.getKind());
    assertEquals("example-task-name", exported.getMetadata().getName());
    assertEquals("Worker", exported.getMetadata().getAnnotations().get("boomerang.io/category"));
  }

  private TektonTask loadTektonTask(String file) throws IOException {
    String yamlString =
        StreamUtils.copyToString(
            new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8);
    ObjectMapper mapper = new YAMLMapper();
    return mapper.readValue(yamlString, TektonTask.class);
  }
}
