package io.boomerang.common.util;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowRun;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;

public class DataAdapterUtil {

  public static final String REDACTED = "*****";

  // A resolved secret shorter than this is not value-scrubbed (scrubbing 1-3 character strings
  // would mangle unrelated text); the name-join blanking still hides it at the workflow level.
  public static final int MIN_SCRUB_LENGTH = 4;
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
   * Mask the value, or the default, of each {@code fieldType} param in place with {@link
   * #maskSecretValue}, and mark it {@code hiddenValue}. A lone Flow reference is left as written.
   *
   * @param isDefaultValue whether the default rather than the value is masked
   */
  public static List<AbstractParam> filterValueByFieldType(
      List<AbstractParam> properties, boolean isDefaultValue, String fieldType) {
    if (properties == null || fieldType == null) {
      return null;
    }

    for (AbstractParam property : properties) {
      filterAbstractParam(property, isDefaultValue, fieldType);
    }
    return properties;
  }

  public static AbstractParam filterAbstractParam(
      AbstractParam param, boolean isDefaultValue, String fieldType) {
    // Redact only when the param actually carries the sensitive type. With `||` here, any non-null
    // param short-circuited true and every param was redacted regardless of type - and a null param
    // with a non-null fieldType entered the block and threw.
    if (param != null && fieldType != null && fieldType.equals(param.getType())) {
      Object hidden = isDefaultValue ? param.getDefaultValue() : param.getValue();
      if (ParameterUtil.isSingleFlowReference(hidden)) {
        return param;
      }
      if (isDefaultValue) {
        param.setDefaultValue(maskSecretValue(param.getDefaultValue()));
      } else {
        param.setValue(maskSecretValue(param.getValue()));
      }
      param.setHiddenValue(Boolean.TRUE);
    }
    return param;
  }

  /**
   * Mask the value and default of each {@code fieldType} param in place with {@link
   * #maskSecretValue}: a workflow's own params, on a definition read.
   */
  public static void filterParamSpecValueByFieldType(List<AbstractParam> params, String fieldType) {
    if (params == null || fieldType == null) {
      return;
    }
    params.stream()
        .filter(p -> fieldType.equals(p.getType()))
        .forEach(
            p -> {
              p.setValue(maskSecretValue(p.getValue()));
              p.setDefaultValue(maskSecretValue(p.getDefaultValue()));
            });
  }

  /**
   * {@link #filterParamSpecValueByFieldType(List, String)} as a NEW list of copies, a set value or
   * default reading as {@code replacement}, for a catalogue task: a model built with
   * BeanUtils.copyProperties shares its spec with the entity it came from. The edit-side partner
   * is {@link #keepParamSpecValueByFieldType}.
   */
  public static List<AbstractParam> filterParamSpecValueByFieldType(
      List<AbstractParam> params, String fieldType, String replacement) {
    if (params == null || fieldType == null) {
      return params;
    }
    return params.stream()
        .map(
            p -> {
              if (!fieldType.equals(p.getType())) {
                return p;
              }
              AbstractParam masked = new AbstractParam();
              BeanUtils.copyProperties(p, masked);
              masked.setDefaultValue(masked(p.getDefaultValue(), replacement));
              masked.setValue(masked(p.getValue(), replacement));
              return masked;
            })
        .collect(Collectors.toList());
  }

  private static Object masked(Object value, String replacement) {
    Object shown = maskSecretValue(value);
    return REDACTED.equals(shown) ? replacement : shown;
  }

  /**
   * The one rule for a secret value a client writes, on every layer: {@link #REDACTED}, or no value
   * at all, keeps the stored value; a blank clears it; anything else replaces it. A marker with
   * nothing stored to keep - or a marker an earlier release stored - is no value, never the secret.
   */
  public static Object keepSecretValue(Object incoming, Object stored) {
    if (incoming == null || REDACTED.equals(incoming)) {
      return REDACTED.equals(stored) ? null : stored;
    }
    return incoming;
  }

  /**
   * {@link #keepSecretValue} for each incoming {@code fieldType} param of a definition, against the
   * stored param of the same name, case-insensitively: its default and its value.
   */
  public static void keepParamSpecValueByFieldType(
      List<AbstractParam> incoming, List<AbstractParam> stored, String fieldType) {
    if (incoming == null || fieldType == null) {
      return;
    }
    incoming.stream()
        .filter(p -> fieldType.equals(p.getType()) && p.getName() != null)
        .forEach(
            p -> {
              AbstractParam match =
                  (stored == null)
                      ? null
                      : stored.stream()
                          .filter(s -> p.getName().equalsIgnoreCase(s.getName()))
                          .findFirst()
                          .orElse(null);
              p.setDefaultValue(
                  keepSecretValue(p.getDefaultValue(), (match != null) ? match.getDefaultValue() : null));
              p.setValue(keepSecretValue(p.getValue(), (match != null) ? match.getValue() : null));
            });
  }

  private static boolean isUnset(Object value) {
    return value == null || (value instanceof String s && s.isBlank());
  }

  /**
   * The one read shape for a secret value, on every layer: a lone Flow reference holds no secret's
   * text and reads as written, an unset value reads as nothing, and a set one as {@link #REDACTED}.
   */
  public static Object maskSecretValue(Object value) {
    if (ParameterUtil.isSingleFlowReference(value)) {
      return value;
    }
    return isUnset(value) ? null : REDACTED;
  }

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
   * Redact by type: every param of {@code paramType} reads as {@link #REDACTED} when set and as
   * nothing when unset. Returns a NEW list of new params rather than mutating in place, because a
   * model built with BeanUtils.copyProperties shares its param list with the entity it came from -
   * so this is safe on a model whose entity is still in use (a submit response, a status event).
   * The type check is authoritative for params; the value scrub stays for free text. A run holds
   * resolved values, so a value that happens to look like a reference is masked like any other.
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
                    ? new RunParam(
                        p.getName(), isUnset(p.getValue()) ? null : REDACTED, p.getType())
                    : p)
        .collect(Collectors.toList());
  }

  /**
   * {@link #filterRunParamValueByFieldType(List, ParamType)} for the values a definition stores -
   * a workflow task node's, a schedule's - with {@link #maskSecretValue}, so a value that is
   * exactly one Flow reference stays as written. The param keeps its type, so the value it
   * resolves to is masked wherever a run shows it.
   */
  public static List<RunParam> filterDefinitionValueByFieldType(
      List<RunParam> params, ParamType paramType) {
    if (params == null || paramType == null) {
      return params;
    }
    return params.stream()
        .map(
            p ->
                paramType.equals(p.getType())
                    ? new RunParam(p.getName(), maskSecretValue(p.getValue()), p.getType())
                    : p)
        .collect(Collectors.toList());
  }

  /**
   * {@link #keepSecretValue} for each incoming param of {@code paramType}, against the stored
   * param of the same name, case-insensitively - a workflow node's, a schedule's. With nothing
   * stored, a marker becomes no value.
   */
  public static void keepRunParamValueByFieldType(
      List<RunParam> incoming, List<RunParam> stored, ParamType paramType) {
    if (incoming == null || paramType == null) {
      return;
    }
    incoming.stream()
        .filter(p -> paramType.equals(p.getType()) && p.getName() != null)
        .forEach(
            p ->
                p.setValue(
                    keepSecretValue(
                        p.getValue(),
                        (stored == null)
                            ? null
                            : stored.stream()
                                .filter(s -> p.getName().equalsIgnoreCase(s.getName()))
                                .findFirst()
                                .map(RunParam::getValue)
                                .orElse(null))));
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
   * Scrub every occurrence of the given secret values from a WorkflowRun MODEL's results, status
   * message and annotations, and from every task on it. Run-wide rather than per task: a secret
   * declared on one task can be substituted into any other task's param, spec field or result.
   * Each scrubbed list, map and result is a new object, so the entity a model was copied from with
   * BeanUtils.copyProperties is left untouched.
   */
  public static void scrubWorkflowRunValues(WorkflowRun run, Set<String> secrets) {
    if (run == null || secrets == null || secrets.isEmpty()) {
      return;
    }
    run.setResults(scrubResults(run.getResults(), secrets));
    run.setStatusMessage(scrubString(run.getStatusMessage(), secrets));
    run.setAnnotations(scrubAnnotations(run.getAnnotations(), secrets));
    if (run.getTasks() != null) {
      run.getTasks().forEach(task -> filterTaskRunValues(task, secrets));
    }
  }

  /**
   * Scrub every occurrence of the given secret values from a TaskRun MODEL's params, results,
   * status message, annotations and spec (script/command/arguments/envs). Each scrubbed part is a
   * new object, so the entity the model was copied from is left untouched - never persist it.
   */
  public static void filterTaskRunValues(TaskRun task, Set<String> secrets) {
    if (task == null || secrets == null || secrets.isEmpty()) {
      return;
    }
    if (task.getParams() != null) {
      task.setParams(
          task.getParams().stream()
              .map(p -> new RunParam(p.getName(), scrubValue(p.getValue(), secrets), p.getType()))
              .collect(Collectors.toList()));
    }
    task.setResults(scrubResults(task.getResults(), secrets));
    task.setStatusMessage(scrubString(task.getStatusMessage(), secrets));
    task.setAnnotations(scrubAnnotations(task.getAnnotations(), secrets));
    TaskRunSpec spec = task.getSpec();
    if (spec != null) {
      TaskRunSpec scrubbed = new TaskRunSpec();
      BeanUtils.copyProperties(spec, scrubbed);
      scrubbed.setScript(scrubString(spec.getScript(), secrets));
      scrubbed.setCommand(scrubStrings(spec.getCommand(), secrets));
      scrubbed.setArguments(scrubStrings(spec.getArguments(), secrets));
      if (spec.getEnvs() != null) {
        scrubbed.setEnvs(
            spec.getEnvs().stream()
                .map(env -> new TaskEnvVar(env.getName(), scrubString(env.getValue(), secrets)))
                .collect(Collectors.toList()));
      }
      task.setSpec(scrubbed);
    }
  }

  private static List<RunResult> scrubResults(List<RunResult> results, Set<String> secrets) {
    if (results == null || secrets == null || secrets.isEmpty()) {
      return results;
    }
    return results.stream()
        .map(r -> new RunResult(r.getName(), r.getDescription(), scrubValue(r.getValue(), secrets)))
        .collect(Collectors.toList());
  }

  private static Map<String, Object> scrubAnnotations(
      Map<String, Object> annotations, Set<String> secrets) {
    if (annotations == null) {
      return null;
    }
    Map<String, Object> scrubbed = new HashMap<>();
    annotations.forEach((key, value) -> scrubbed.put(key, scrubValue(value, secrets)));
    return scrubbed;
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

  /**
   * Every occurrence of the given secret values in {@code value} replaced with {@link #REDACTED}:
   * the value scrub the run reads apply to free text, for a caller holding one string.
   */
  public static String scrubString(String value, Set<String> secrets) {
    if (value == null || secrets == null) {
      return value;
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

  // The name join for runs stored before params carried a type: each run param a `fieldType` spec
  // param names reads as a set secret does, REDACTED, or as nothing when unset.
  public static void filterRunParamValueByFieldType(
      List<AbstractParam> config, List<RunParam> params, String fieldType) {
    config.stream()
        .filter(c -> fieldType.equals(c.getType()))
        .forEach(
            c -> {
              c.setValue(maskSecretValue(c.getValue()));
              params.stream()
                  .filter(param -> param.getName().equalsIgnoreCase(c.getName()))
                  .forEach(p -> p.setValue(isUnset(p.getValue()) ? null : REDACTED));
            });
  }
}
