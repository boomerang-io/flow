package io.boomerang.workflow.tekton;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamSpec;
import io.boomerang.common.model.Task;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A task's params keep their defaults and descriptions through the Tekton YAML: both ride on
 * {@code spec.params}, the {@code boomerang.io/params} annotation carries the UI fields only, and
 * the conversion never changes the task it reads.
 */
class TektonConverterParamsRoundTripTest {

  @Test
  void specParamsCarryDefaultsAndDescriptionsAndTheAnnotationIsSlimmed() {
    Task task = task();

    TektonTask tektonTask = TektonConverter.convertTaskTemplateToTektonTask(task);

    ParamSpec region = specParam(tektonTask, "region");
    assertThat(region.getDefaultValue()).isEqualTo("eu-west-1");
    assertThat(region.getDescription()).isEqualTo("Where the cluster runs");
    assertThat(specParam(tektonTask, "registryToken").getDefaultValue()).isEqualTo("*****");

    @SuppressWarnings("unchecked")
    List<AbstractParam> annotation =
        (List<AbstractParam>) tektonTask.getMetadata().getAnnotations().get("boomerang.io/params");
    assertThat(annotation).extracting(AbstractParam::getDefaultValue).containsOnlyNulls();
    assertThat(annotation).extracting(AbstractParam::getDescription).containsOnlyNulls();
    assertThat(annotation).extracting(AbstractParam::getLabel).containsExactly("Registry token", "Region");
  }

  @Test
  void theConversionDoesNotChangeTheTaskItReads() {
    Task task = task();

    TektonConverter.convertTaskTemplateToTektonTask(task);

    AbstractParam region = task.getSpec().getParams().get(1);
    assertThat(region.getDefaultValue()).isEqualTo("eu-west-1");
    assertThat(region.getDescription()).isEqualTo("Where the cluster runs");
  }

  @Test
  void aYamlRoundTripRestoresEveryParamField() {
    Task task = task();

    // Through the serialised YAML shape, as a client sends it back: annotations become plain maps.
    TektonTask yaml =
        JsonMapper.builder()
            .build()
            .convertValue(TektonConverter.convertTaskTemplateToTektonTask(task), TektonTask.class);
    Task restored = TektonConverter.convertTektonTaskToTaskTemplate(yaml);

    assertThat(restored.getSpec().getParams())
        .usingRecursiveFieldByFieldElementComparatorOnFields(
            "name", "type", "label", "defaultValue", "description", "required")
        .containsExactlyElementsOf(task.getSpec().getParams());
  }

  private static Task task() {
    Task task = new Task();
    task.setName("deploy");
    task.getSpec().setImage("busybox:latest");
    task.getSpec()
        .setParams(
            new LinkedList<>(
                List.of(
                    param("registryToken", "password", "Registry token", "*****", "Pushes the image"),
                    param("region", "text", "Region", "eu-west-1", "Where the cluster runs"))));
    return task;
  }

  private static AbstractParam param(
      String name, String type, String label, Object defaultValue, String description) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setLabel(label);
    param.setDefaultValue(defaultValue);
    param.setDescription(description);
    param.setRequired(false);
    return param;
  }

  private static ParamSpec specParam(TektonTask tektonTask, String name) {
    return tektonTask.getSpec().getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no spec param named " + name));
  }
}
