package io.boomerang.common.util;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowRun;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DataAdapterUtil {

  public static final String REDACTED = "*****";

  // A resolved secret shorter than this is not value-scrubbed (scrubbing 1-3 character strings
  // would mangle unrelated text); the name-join blanking still hides it at the workflow level.
  private static final int MIN_SCRUB_LENGTH = 4;
  public enum FieldType {
    PASSWORD("password");

    private final String value;

    private FieldType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /**
   * Method for filtering sensitive data from AbstractConfigs (e.g. make null the value of any
   * password type field)
   *
   * @param properties
   * @param isDefaultValue - Specify if the defaultValue or the value should be made null
   * @param fieldType
   * @return
   */
  public static List<AbstractParam> filterValueByFieldType(
      List<AbstractParam> properties, boolean isDefaultValue, String fieldType) {
    if (properties == null || fieldType == null) {
      return null;
    }

    for (AbstractParam property : properties) {
      if (!fieldType.equals(property.getType())) {
        continue;
      }
      if (isDefaultValue) {
        property.setDefaultValue(null);
      } else {
        property.setValue(null);
      }
      property.setHiddenValue(Boolean.TRUE);
    }
    return properties;
  }

  public static AbstractParam filterAbstractParam(
      AbstractParam param, boolean isDefaultValue, String fieldType) {
    // Redact only when the param actually carries the sensitive type. With `||` here, any non-null
    // param short-circuited true and every param was redacted regardless of type - and a null param
    // with a non-null fieldType entered the block and threw.
    if (param != null && fieldType != null && fieldType.equals(param.getType())) {
      if (isDefaultValue) {
        param.setDefaultValue(null);
      } else {
        param.setValue(null);
      }
      param.setHiddenValue(Boolean.TRUE);
    }
    return param;
  }

  /**
   * Method for filtering sensitive data from Parameters based on AbstractConfig type (e.g. make
   * null the value of any password type field)
   *
   * @param params
   * @param fieldType
   * @return
   */
  public static void filterParamSpecValueByFieldType(List<AbstractParam> params, String fieldType) {
    params.stream()
        .filter(p -> fieldType.equals(p.getType()))
        .forEach(
            p -> {
              p.setValue("");
              p.setDefaultValue("");
            });
  }

  /**
   * Method for filtering sensitive data from Parameters based on AbstractConfig type (e.g. make
   * null the value of any password type field)
   *
   * @param properties
   * @param fieldType
   * @return
   */
  /**
   * The resolved values of the params whose SPEC type matches fieldType, joined by name
   * (case-insensitive, matching filterRunParamValueByFieldType). These are the strings that must
   * not appear anywhere in a display payload - after resolution a workflow-level password can sit
   * inside any task param, spec field, or result under a different name.
   */
  public static Set<String> sensitiveValues(
      List<AbstractParam> specParams, List<RunParam> runParams, String fieldType) {
    if (specParams == null || runParams == null || fieldType == null) {
      return Set.of();
    }
    Set<String> names =
        specParams.stream()
            .filter(c -> fieldType.equals(c.getType()))
            .map(AbstractParam::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.toSet());
    return runParams.stream()
        .filter(p -> p.getName() != null && names.contains(p.getName().toLowerCase(java.util.Locale.ROOT)))
        .map(RunParam::getValue)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .filter(v -> !v.isBlank())
        .collect(Collectors.toSet());
  }

  /**
   * Filter sensitive data from a WorkflowRun MODEL: the same name-join blanking
   * filterRunParamValueByFieldType performs on the run's own params, then a value scrub of the
   * resolved secrets from the run's results and tasks (where substitution can place them under any
   * name). Mutates the model only - callers must never persist a filtered object.
   */
  public static void filterWorkflowRunValueByFieldType(
      WorkflowRun run, List<AbstractParam> specParams, String fieldType) {
    if (run == null) {
      return;
    }
    // Collect the resolved values BEFORE the name-join blanks them.
    Set<String> secrets = sensitiveValues(specParams, run.getParams(), fieldType);
    if (specParams != null && run.getParams() != null) {
      filterRunParamValueByFieldType(specParams, run.getParams(), fieldType);
    }
    scrubResults(run.getResults(), secrets);
    if (run.getTasks() != null) {
      run.getTasks().forEach(task -> filterTaskRunValues(task, secrets));
    }
  }

  /**
   * Scrub every occurrence of the given secret values from a TaskRun MODEL's params, results and
   * spec (script/command/arguments/envs). Mutates the model only - never persist it.
   */
  public static void filterTaskRunValues(TaskRun task, Set<String> secrets) {
    if (task == null || secrets == null || secrets.isEmpty()) {
      return;
    }
    if (task.getParams() != null) {
      task.getParams().forEach(p -> p.setValue(scrubValue(p.getValue(), secrets)));
    }
    scrubResults(task.getResults(), secrets);
    TaskRunSpec spec = task.getSpec();
    if (spec != null) {
      spec.setScript(scrubString(spec.getScript(), secrets));
      spec.setCommand(scrubStrings(spec.getCommand(), secrets));
      spec.setArguments(scrubStrings(spec.getArguments(), secrets));
      if (spec.getEnvs() != null) {
        spec.getEnvs().forEach(env -> env.setValue(scrubString(env.getValue(), secrets)));
      }
    }
  }

  private static void scrubResults(List<RunResult> results, Set<String> secrets) {
    if (results != null && secrets != null && !secrets.isEmpty()) {
      results.forEach(r -> r.setValue(scrubValue(r.getValue(), secrets)));
    }
  }

  private static Object scrubValue(Object value, Set<String> secrets) {
    if (value == null) {
      return null;
    }
    if (value instanceof String stringValue) {
      return scrubString(stringValue, secrets);
    }
    // Non-string values (objects, arrays, numbers) are replaced wholesale when their string form
    // carries a secret - conservative, but a partial scrub of a structured value is worse.
    String asString = value.toString();
    return scrubString(asString, secrets).equals(asString) ? value : REDACTED;
  }

  private static String scrubString(String value, Set<String> secrets) {
    if (value == null) {
      return null;
    }
    String scrubbed = value;
    for (String secret : secrets) {
      if (secret.length() >= MIN_SCRUB_LENGTH) {
        scrubbed = scrubbed.replace(secret, REDACTED);
      }
    }
    return scrubbed;
  }

  private static List<String> scrubStrings(List<String> values, Set<String> secrets) {
    if (values == null) {
      return null;
    }
    return values.stream().map(v -> scrubString(v, secrets)).collect(Collectors.toList());
  }

  public static void filterRunParamValueByFieldType(
      List<AbstractParam> config, List<RunParam> params, String fieldType) {
    if (config.stream().anyMatch(c -> fieldType.equals(c.getType()))) {
      config.stream()
          .filter(c -> fieldType.equals(c.getType()))
          .forEach(
              c -> {
                c.setValue("");
                params.stream()
                    .filter(param -> param.getName().equalsIgnoreCase((c.getName())))
                    .forEach(
                        p -> {
                          p.setValue("");
                        });
              });
    }
  }
}
