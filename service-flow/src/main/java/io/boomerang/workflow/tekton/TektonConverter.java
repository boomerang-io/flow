package io.boomerang.workflow.tekton;

import static io.boomerang.common.util.ParameterUtil.getTektonParamType;

import io.boomerang.common.enums.ConfigType;
import io.boomerang.common.model.*;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.BeanUtils;

public class TektonConverter {

  private TektonConverter() {}

  public static TektonTask convertTaskTemplateToTektonTask(Task task) {
    TektonTask tektonTask = new TektonTask();
    tektonTask.setApiVersion("tekton.dev/v1beta1");
    tektonTask.setKind("Task");

    Metadata metadata = new Metadata();
    metadata.setName(task.getName());
    metadata.setLabels(new HashMap<String, String>());

    List<AbstractParam> paramAnnotation = new LinkedList<>();
    if (task.getSpec().getParams() != null && !task.getSpec().getParams().isEmpty()) {
      task.getSpec()
          .getParams()
          .forEach(
              ap -> {
                ap.setDescription(null);
                ap.setDefaultValue(null);
                paramAnnotation.add(ap);
              });
    }

    Map<String, Object> annotations = metadata.getAnnotations();
    annotations.putAll(task.getAnnotations());
    annotations.put("boomerang.io/icon", task.getIcon());
    annotations.put("boomerang.io/params", paramAnnotation);
    annotations.put("boomerang.io/category", task.getCategory());
    annotations.put("boomerang.io/displayName", task.getDisplayName());
    annotations.put("boomerang.io/version", task.getVersion());
    annotations.put("boomerang.io/verified", task.isVerified());
    tektonTask.setMetadata(metadata);

    Spec spec = new Spec();
    spec.setDescription(task.getDescription());

    Step step = new Step();
    step.setName(task.getName());
    step.setImage(task.getSpec().getImage());
    step.setScript(task.getSpec().getScript());
    step.setWorkingDir(task.getSpec().getWorkingDir());
    step.setEnv(task.getSpec().getEnvs());
    step.setCommand(task.getSpec().getCommand());
    step.setArgs(task.getSpec().getArguments());

    List<Step> steps = new LinkedList<>();
    steps.add(step);
    spec.setSteps(steps);

    List<ParamSpec> params = new LinkedList<>();
    if (task.getSpec().getParams() != null) {
      for (AbstractParam param : task.getSpec().getParams()) {
        ParamSpec tektonParam = new ParamSpec();
        BeanUtils.copyProperties(param, tektonParam, "type");
        tektonParam.setType(getTektonParamType(param.getType()));
        params.add(tektonParam);
      }
    }
    spec.setParams(params);
    spec.setResults(task.getSpec().getResults());
    tektonTask.setSpec(spec);

    return tektonTask;
  }

  /*
   * Converts a TektonTask to a Flow TaskTemplate.
   *
   * Version and Verified need to be set outside of this method once its known if the task exists
   * and the requestor has access to the task.
   *
   * TODO: figure out how Type is set
   */
  public static Task convertTektonTaskToTaskTemplate(TektonTask tektonTask) {
    Task task = new Task();

    Metadata metadata = tektonTask.getMetadata();
    task.setName(metadata.getName());
    task.setLabels(metadata.getLabels());

    List<AbstractParam> annotationParams = new LinkedList<>();
    if (metadata.getAnnotations() != null && !metadata.getAnnotations().isEmpty()) {
      Map<String, Object> annotations = metadata.getAnnotations();
      Object icon = annotations.get("boomerang.io/icon");
      if (icon != null) {
        task.setIcon(icon.toString());
      }
      annotations.remove("boomerang.io/icon");
      Object category = annotations.get("boomerang.io/category");
      if (category != null) {
        task.setCategory(category.toString());
      }
      annotations.remove("boomerang.io/category");
      // Check if description is set as an annotation. It will be overridden if the Spec has the
      // optional description
      Object description = annotations.get("description");
      if (description != null) {
        task.setDescription(description.toString());
      }
      annotations.remove("description");
      Object displayName = annotations.get("boomerang.io/displayName");
      if (displayName != null) {
        task.setDisplayName(displayName.toString());
      }
      annotations.remove("boomerang.io/displayName");
      Object version = annotations.get("boomerang.io/version");
      if (version != null) {
        task.setVersion((Integer) version);
      }
      annotations.remove("boomerang.io/version");
      annotations.remove("boomerang.io/verified");
      Object abstractParams = annotations.get("boomerang.io/params");
      if (abstractParams != null) {
        annotationParams = (List<AbstractParam>) abstractParams;
      }
      annotations.remove("boomerang.io/params");
    }

    Spec spec = tektonTask.getSpec();
    if (spec.getDescription() != null && !spec.getDescription().isBlank()) {
      task.setDescription(spec.getDescription());
    }
    Step step = spec.getSteps().get(0);
    if (step.getImage() != null && !step.getImage().isBlank()) {
      task.getSpec().setImage(step.getImage());
    }
    if (step.getScript() != null && !step.getScript().isBlank()) {
      task.getSpec().setScript(step.getScript());
    }
    if (step.getWorkingDir() != null && !step.getWorkingDir().isBlank()) {
      task.getSpec().setWorkingDir(step.getWorkingDir());
    }
    if (step.getEnv() != null && !step.getEnv().isEmpty()) {
      task.getSpec().setEnvs(step.getEnv());
    }
    if (step.getCommand() != null && !step.getCommand().isEmpty()) {
      task.getSpec().setCommand(step.getCommand());
    }
    if (step.getArgs() != null && !step.getArgs().isEmpty()) {
      task.getSpec().setArguments(step.getArgs());
    }

    if (spec.getParams() != null && !spec.getParams().isEmpty()) {
      List<AbstractParam> params = new LinkedList<>();
      for (ParamSpec tektonParam : spec.getParams()) {
        AbstractParam param = new AbstractParam();
        BeanUtils.copyProperties(tektonParam, param, "type");
        // TODO: check if types still are valid between the Tekton Model and the AbstractParam in
        // the
        // Annotation
        Optional<AbstractParam> optionalAbstractParam =
            annotationParams.stream()
                .filter(ap -> ap.getName().equals(tektonParam.getName()))
                .findFirst();
        if (optionalAbstractParam.isPresent()) {
          // TODO: does it get stored as default or defaultValue in the annotation
          BeanUtils.copyProperties(
              optionalAbstractParam.get(), param, "defaultValue", "description", "name");
          // Legacy - might not be needed with model deserialisation of TextArea and CodeEditor
          // if (defaultStr instanceof ArrayList<?>){
          // ArrayList<String> values = (ArrayList<String>) defaultStr;
          // StringBuilder sb = new StringBuilder();
          // for (String line : values) {
          // sb.append(line);
          // sb.append('\n');
          // newConfig.setDefaultValue(sb.toString());
          // }
          // }
        } else {
          param.setLabel(tektonParam.getName());
          param.setReadOnly(false);
          switch (tektonParam.getType()) {
            case array -> param.setType(ConfigType.MULTISELECT.getLabel());
            case object -> param.setType(ConfigType.JSON.getLabel());
            default -> param.setType(ConfigType.TEXT.getLabel());
          }
        }
        params.add(param);
      }
      task.getSpec().setParams(params);
    }

    if (spec.getResults() != null && !spec.getResults().isEmpty()) {
      spec.setResults(spec.getResults());
    }

    ChangeLog changelog = new ChangeLog();
    changelog.setDate(new Date());
    task.setChangelog(changelog);
    return task;
  }
}
