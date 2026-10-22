package io.boomerang.workflow.tekton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.boomerang.common.model.Task;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Task's category MUST survive the Tekton YAML round-trip: it rides in the
 * {@code boomerang.io/category} annotation on the way out and is lifted back onto the typed
 * field (and removed from the annotation map) on the way in. A Tekton task with no such
 * annotation yields no category rather than an error.
 */
class TektonConverterCategoryRoundTripTest {

  @Test
  void categorySurvivesTheTektonRoundTrip() {
    Task task = new Task();
    task.setName("servicenow-create-incident");
    task.setCategory("ServiceNow");
    task.getSpec().setImage("ubuntu");

    TektonTask tektonTask = TektonConverter.convertTaskTemplateToTektonTask(task);
    assertThat(tektonTask.getMetadata().getAnnotations())
        .containsEntry("boomerang.io/category", "ServiceNow");

    Task restored = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);

    assertThat(restored.getCategory()).isEqualTo("ServiceNow");
    assertThat(tektonTask.getMetadata().getAnnotations()).doesNotContainKey("boomerang.io/category");
    assertThat(restored.getAnnotations()).doesNotContainKey("boomerang.io/category");
  }

  @Test
  void aTektonTaskWithoutTheCategoryAnnotationYieldsNoCategory() {
    TektonTask tektonTask = new TektonTask();
    TektonMetadata metadata = new TektonMetadata();
    metadata.setName("plain-task");
    metadata.getAnnotations().put("description", "no category here");
    tektonTask.setMetadata(metadata);
    TektonSpec spec = new TektonSpec();
    Step step = new Step();
    step.setName("plain-task");
    step.setImage("ubuntu");
    spec.setSteps(List.of(step));
    tektonTask.setSpec(spec);

    assertThatCode(() -> TektonConverter.convertTektonTaskToTaskTemplate(tektonTask))
        .doesNotThrowAnyException();
    assertThat(TektonConverter.convertTektonTaskToTaskTemplate(tektonTask).getCategory()).isNull();
  }
}
