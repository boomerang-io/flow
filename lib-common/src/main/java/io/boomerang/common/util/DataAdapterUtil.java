package io.boomerang.common.util;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowRun;
import java.util.HashSet;
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
   * The values of the run params whose OWN type is {@code paramType} - the run-time counterpart of
   * the spec join above. A secret-typed param needs no definition to be recognised: it may have
   * been sent as a secret on the run request, or become one when a secret was substituted into it.
   */
  public static Set<String> sensitiveValues(List<RunParam> runParams, ParamType paramType) {
    if (runParams == null || paramType == null) {
      return Set.of();
    }
    return runParams.stream()
        .filter(p -> paramType.equals(p.getType()))
        .map(RunParam::getValue)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .filter(v -> !v.isBlank())
        .collect(Collectors.toSet());
  }

  /**
   * The values of every {@code paramType} param on a WorkflowRun MODEL and on each of its tasks, so
   * a caller can scrub them from free text (results, scripts, logs) where they appear under no
   * param at all. Read-only.
   */
  public static Set<String> sensitiveValues(WorkflowRun run, ParamType paramType) {
    if (run == null) {
      return Set.of();
    }
    Set<String> values = new HashSet<>(sensitiveValues(run.getParams(), paramType));
    if (run.getTasks() != null) {
      run.getTasks().forEach(task -> values.addAll(sensitiveValues(task.getParams(), paramType)));
    }
    return values;
  }

  /**
   * Redact by type: every param of {@code paramType} has its value replaced with {@link #REDACTED}.
   * Returns a NEW list of new params rather than mutating in place, because a model built with
   * BeanUtils.copyProperties shares its param list with the entity it came from - so this is safe
   * on a model whose entity is still in use (a submit response, a status event). The type check is
   * authoritative for params; the value scrub stays for free text.
   */
  public static List<RunParam> filterRunParamValueByFieldType(
      List<RunParam> params, ParamType paramType) {
    if (params == null || paramType == null) {
      return params;
    }
    return params.stream()
        .map(
            p ->
                paramType.equals(p.getType())
                    ? new RunParam(p.getName(), REDACTED, p.getType())
                    : p)
        .collect(Collectors.toList());
  }

  /** {@link #filterRunParamValueByFieldType(List, ParamType)} on one TaskRun MODEL's params. */
  public static void filterTaskRunValueByFieldType(TaskRun task, ParamType paramType) {
    if (task != null) {
      task.setParams(filterRunParamValueByFieldType(task.getParams(), paramType));
    }
  }

  /**
   * {@link #filterRunParamValueByFieldType(List, ParamType)} on a WorkflowRun MODEL's own params
   * and on the params of every task attached to it.
   */
  public static void filterWorkflowRunValueByFieldType(WorkflowRun run, ParamType paramType) {
    if (run == null) {
      return;
    }
    run.setParams(filterRunParamValueByFieldType(run.getParams(), paramType));
    if (run.getTasks() != null) {
      run.getTasks().forEach(task -> filterTaskRunValueByFieldType(task, paramType));
    }
  }

  /**
   * Filter sensitive data from a WorkflowRun MODEL: the same name-join blanking
   * filterRunParamValueByFieldType performs on the run's own params, then a value scrub of the
   * resolved secrets from the run's results and tasks (where substitution can place them under any
   * name). Mutates the model only - callers must never persist a filtered object.
   */
  public static Set<String> filterWorkflowRunValueByFieldType(
      WorkflowRun run, List<AbstractParam> specParams, String fieldType) {
    if (run == null) {
      return Set.of();
    }
    // Collect the resolved values BEFORE the name-join blanks them.
    Set<String> secrets = sensitiveValues(specParams, run.getParams(), fieldType);
    if (specParams != null && run.getParams() != null) {
      filterRunParamValueByFieldType(specParams, run.getParams(), fieldType);
    }
    scrubWorkflowRunValues(run, secrets);
    return secrets;
  }

  /**
   * Filter sensitive data from a TaskRun MODEL against the CATALOGUE TASK's own param spec - the
   * second type authority beside the workflow revision, and the only one that marks a value typed
   * straight into a task node's password-typed param (that param has no workflow-level param to
   * join against). Blanks those params by name, the same way
   * {@link #filterRunParamValueByFieldType} does at the workflow level, and RETURNS the resolved
   * values so the caller can scrub them RUN-WIDE: substitution carries one into a downstream
   * task's param or result under another name. Mutates the model only - callers must never persist
   * a filtered object.
   */
  public static Set<String> filterTaskRunValueByFieldType(
      TaskRun task, List<AbstractParam> specParams, String fieldType) {
    if (task == null || specParams == null || task.getParams() == null) {
      return Set.of();
    }
    // Collect the resolved values BEFORE the name-join blanks them.
    Set<String> secrets = sensitiveValues(specParams, task.getParams(), fieldType);
    filterRunParamValueByFieldType(specParams, task.getParams(), fieldType);
    return secrets;
  }

  /**
   * Scrub every occurrence of the given secret values from a WorkflowRun MODEL's results and from
   * every task on it. Run-wide rather than per task: a secret declared on one task can be
   * substituted into any other task's param, spec field or result. Mutates the model only.
   */
  public static void scrubWorkflowRunValues(WorkflowRun run, Set<String> secrets) {
    if (run == null || secrets == null || secrets.isEmpty()) {
      return;
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
