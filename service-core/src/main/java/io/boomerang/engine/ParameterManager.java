package io.boomerang.engine;

import tools.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.workflow.ParamLayerService;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/*
 * Handles Parameter Substitution and Propagation
 *
 * Currently only Params of dot notation -> $(params.name)
 *
 * Future: bracket notation patterns -> params['<param name>'] and params["<param name>"]
 *
 * Ref: https://github.com/tektoncd/pipeline/blob/main/pkg/substitution/substitution.go Ref:
 * https://tekton.dev/docs/pipelines/variables/#fields-that-accept-variable-substitutions
 */
@Service
public class ParameterManager {
  private static final Logger LOGGER = LogManager.getLogger();

  // One compiled form of the reference pattern, shared by the discovery loop and the
  // single-reference test, so the two cannot drift apart.
  private static final Pattern DOT_NOTATION_PATTERN = Pattern.compile("(?<=\\$\\().+?(?=\\))");
  // Ceiling on the structural walk in replaceStringInObject: only string leaves are substituted,
  // so a pathological nesting cannot exhaust the stack.
  private static final int MAX_SUBSTITUTION_DEPTH = 32;
  private final String[] reservedScope = {
    "global",
    ParamLayers.WORKSPACE_SCOPE,
    ParamLayers.DEPRECATED_WORKSPACE_SCOPE,
    "workflow",
    "context"
  };
  // Each deprecated team.params reference is logged once per instance, so a busy schedule doesn't
  // repeat the warning on every run.
  private static final Set<String> warnedDeprecatedReferences = ConcurrentHashMap.newKeySet();

  // A run started by one of these carries values nobody in the workspace wrote - a webhook, event
  // or GitHub payload, or values already resolved by a parent run or the run being retried - so its
  // params that differ from the revision's defaults are inserted as written, never expanded.
  private static final Set<String> SUPPLIED_VALUE_TRIGGERS =
      Set.of(
          TriggerEnum.webhook.getTrigger(),
          TriggerEnum.event.getTrigger(),
          TriggerEnum.github.getTrigger(),
          TriggerEnum.task.getTrigger(),
          TriggerEnum.retry.getTrigger());
  private static final String WORKSPACE_NAME_ANNOTATION = "boomerang.io/workspace-name";

  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final ObjectMapper objectMapper;
  private final ParamLayerService paramLayerService;
  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;

  public ParameterManager(
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      ObjectMapper objectMapper,
      ParamLayerService paramLayerService,
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository) {
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.objectMapper = objectMapper;
    this.paramLayerService = paramLayerService;
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
  }

  /*
   * The layers one resolution reads, and the run params whose values were supplied rather than
   * written in the workspace (lower-cased, as references match case-insensitively).
   */
  private record Layers(ParamLayers paramLayers, Set<String> suppliedParams) {}

  /*
   * Resolve all RunParams for either WorkflowRun or TaskRun
   */
  public void resolveParamLayers(WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    Layers paramLayers = buildParameterLayering(wfRun, optTaskRun);
    // Memo of upstream TaskRun lookups for the duration of one resolution: a param string can
    // reference the same task's results many times, and upstream results are final by now.
    Map<String, Optional<TaskRunEntity>> taskRunMemo = new HashMap<>();
    List<RunParam> runParams;
    String wfRunId = wfRun.getId();
    if (optTaskRun.isPresent()) {
      runParams = optTaskRun.get().getParams();
    } else {
      runParams = wfRun.getParams();
    }
    runParams.stream()
        .forEach(
            p -> {
              // A supplied value is a run input that arrived from outside the workspace: it is kept
              // as it arrived, never resolved.
              if (optTaskRun.isEmpty() && isSuppliedParam(p.getName(), paramLayers)) {
                return;
              }
              LOGGER.debug(
                  "Resolving Parameters: " + p.getName() + "(" + p.getType() == null
                      ? "string"
                      : p.getType() + ") = " + p.getValue());
              if (ParamType.string.equals(p.getType()) || p.getType() == null) {
                // Default to String replacement. This also allows recursive use of Params and
                // multiple Param replacement
                p.setValue(
                    resolveParam(
                        ParamType.string,
                        p.getValue() != null ? p.getValue().toString() : "",
                        wfRunId,
                        paramLayers, taskRunMemo));
              } else if (ParamType.array.equals(p.getType()) && p.getValue() instanceof List) {
                // Type safety. If you attempt to convert a string or object (JSON = HashMap) then
                // this causes an exception
                ArrayList<String> valueList = (ArrayList<String>) p.getValue();
                p.setValue(
                    valueList.stream()
                        .map(v -> resolveParam(ParamType.string, v, wfRunId, paramLayers, taskRunMemo))
                        .collect(Collectors.toList()));
              } else if (ParamType.object.equals(p.getType())) {
                // Replace Param with Object. Treated as JSON and allows for the extra JSONPath
                // retrieval.
                p.setValue(resolveParam(p.getType(), p.getValue(), wfRunId, paramLayers, taskRunMemo));
              }
            });
    // Return WorkflowRun or TaskRun RunParams
    if (optTaskRun.isPresent()) {
      optTaskRun.get().setParams(runParams);
      // The spec is part of the contract too: $(params.x) in script/command/arguments/envs must
      // resolve identically on every executor, so it happens here rather than relying on
      // Tekton's controller-side substitution (which Kubernetes Jobs and Docker do not have).
      resolveSpec(optTaskRun.get().getSpec(), wfRun.getId(), paramLayers, taskRunMemo);
    } else {
      wfRun.setParams(runParams);
    }
  }

  /*
   * Resolve one value against the TaskRun's parameter layers, as an object-typed param resolves: a
   * value that is exactly one reference becomes the referenced value itself (a result array stays
   * an array), and references inside a structure are substituted into its string leaves.
   */
  public Object resolveParamValue(WorkflowRunEntity wfRun, TaskRunEntity taskRun, Object value) {
    return resolveParam(
        ParamType.object,
        value,
        wfRun.getId(),
        buildParameterLayering(wfRun, Optional.of(taskRun)),
        new HashMap<>());
  }

  /*
   * Resolve $(params.x) references inside the TaskRun spec's string fields. Same one-pass
   * semantics as a param value referencing another param; unresolved references are left as-is.
   */
  private void resolveSpec(
      TaskRunSpec spec,
      String wfRunId,
      Layers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo) {
    if (spec == null) {
      return;
    }
    spec.setScript(resolveString(spec.getScript(), wfRunId, paramLayers, taskRunMemo));
    spec.setCommand(resolveStrings(spec.getCommand(), wfRunId, paramLayers, taskRunMemo));
    spec.setArguments(resolveStrings(spec.getArguments(), wfRunId, paramLayers, taskRunMemo));
    if (spec.getEnvs() != null) {
      spec.getEnvs()
          .forEach(
              env -> env.setValue(resolveString(env.getValue(), wfRunId, paramLayers, taskRunMemo)));
    }
  }

  private String resolveString(
      String value,
      String wfRunId,
      Layers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo) {
    if (value == null) {
      return null;
    }
    Object resolved = resolveParam(ParamType.string, value, wfRunId, paramLayers, taskRunMemo);
    return resolved != null ? resolved.toString() : null;
  }

  private List<String> resolveStrings(
      List<String> values,
      String wfRunId,
      Layers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo) {
    if (values == null) {
      return null;
    }
    return values.stream()
        .map(v -> resolveString(v, wfRunId, paramLayers, taskRunMemo))
        .collect(Collectors.toList());
  }

  /*
   * Build all parameter layers as an object of Maps. The global, workspace and context layers are
   * read from their stores now (ParamLayerService), against the run's workspace, its workflow and
   * the revision it runs; the run, task and per-run context keys are added here.
   *
   * If you only pass it the Workflow Run Entity, it won't add the Task Run Params to the map
   */
  private Layers buildParameterLayering(
      WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    Object workspace = wfRun.getAnnotations().get(WORKSPACE_NAME_ANNOTATION);
    WorkflowEntity workflow =
        wfRun.getWorkflowRef() != null
            ? workflowRepository.findById(wfRun.getWorkflowRef()).orElse(null)
            : null;
    WorkflowRevisionEntity revision =
        wfRun.getWorkflowRevisionRef() != null
            ? workflowRevisionRepository.findById(wfRun.getWorkflowRevisionRef()).orElse(null)
            : null;
    ParamLayers paramLayers =
        paramLayerService.buildParamLayers(
            workspace != null ? workspace.toString() : null, workflow, revision);

    // Override particular context Parameters. Additional Context Params come from the Workflow
    // service.
    Map<String, Object> contextParams = paramLayers.getContextParams();
    contextParams.put("workflowrun-trigger", wfRun.getTrigger());
    contextParams.put(
        "workflowrun-initiator",
        Objects.isNull(wfRun.getInitiatedByRef()) || wfRun.getInitiatedByRef().isBlank()
            ? ""
            : wfRun.getInitiatedByRef());
    contextParams.put("workflowrun-ref", wfRun.getId());
    if (optTaskRun.isPresent()) {
      contextParams.put("taskrun-ref", optTaskRun.get().getId());
      contextParams.put("taskrun-name", optTaskRun.get().getName());
      contextParams.put("taskrun-type", optTaskRun.get().getType());
    }
    if (wfRun.getParams() != null && !wfRun.getParams().isEmpty()) {
      paramLayers.setWorkflowParams(ParameterUtil.runParamListToMap(wfRun.getParams()));
    }
    if (optTaskRun.isPresent()
        && optTaskRun.get().getParams() != null
        && !optTaskRun.get().getParams().isEmpty()) {
      paramLayers.setTaskParams(ParameterUtil.runParamListToMap(optTaskRun.get().getParams()));
    }

    return new Layers(paramLayers, suppliedParamNames(wfRun, revision));
  }

  /*
   * The run params whose values were supplied from outside the workspace: on a run started by one of
   * SUPPLIED_VALUE_TRIGGERS, every param whose value is not its revision default - a param the
   * revision does not declare, such as a webhook's data, included. Lower-cased.
   */
  private static Set<String> suppliedParamNames(
      WorkflowRunEntity wfRun, WorkflowRevisionEntity revision) {
    if (wfRun.getTrigger() == null
        || !SUPPLIED_VALUE_TRIGGERS.contains(wfRun.getTrigger())
        || wfRun.getParams() == null) {
      return Set.of();
    }
    Map<String, Object> defaults = new HashMap<>();
    if (revision != null && revision.getParams() != null) {
      revision.getParams().stream()
          .filter(param -> param.getName() != null)
          .forEach(param -> defaults.put(param.getName().toLowerCase(), param.getDefaultValue()));
    }
    return wfRun.getParams().stream()
        .filter(param -> param.getName() != null)
        .filter(
            param -> {
              String name = param.getName().toLowerCase();
              return !defaults.containsKey(name)
                  || !Objects.equals(defaults.get(name), param.getValue());
            })
        .map(param -> param.getName().toLowerCase())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean isSuppliedParam(String name, Layers layers) {
    return name != null && layers.suppliedParams().contains(name.toLowerCase());
  }

  /*
   * Whether the value behind a reference key must be inserted as written: a task result - a task's
   * output - or a supplied run param. $(params.x) reads the nearest layer, so a supplied run param
   * serves it only when neither the task nor the context defines x.
   */
  private static boolean insertedAsWritten(String key, Layers layers) {
    String[] parts = key.split("\\.");
    if (parts.length >= 4
        && "tasks".equalsIgnoreCase(parts[0])
        && "results".equalsIgnoreCase(parts[2])) {
      return true;
    }
    if (layers.suppliedParams().isEmpty()) {
      return false;
    }
    if (parts.length >= 3
        && "workflow".equalsIgnoreCase(parts[0])
        && "params".equalsIgnoreCase(parts[1])) {
      return isSuppliedParam(parts[2], layers);
    }
    if (parts.length >= 2 && "params".equalsIgnoreCase(parts[0])) {
      return isSuppliedParam(parts[1], layers)
          && !definesIgnoringCase(layers.paramLayers().getTaskParams(), parts[1])
          && !definesIgnoringCase(layers.paramLayers().getContextParams(), parts[1]);
    }
    return false;
  }

  private static boolean definesIgnoringCase(Map<String, Object> layer, String name) {
    return layer != null && layer.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
  }

  /*
   * Escape every reference opening in a value inserted as written, so the substitutor emits "$(" as
   * text instead of expanding it ("$$(" is its escape for "$(").
   */
  private static String escapeReferences(String value) {
    return value.replace("$(", "$$(");
  }

  /*
   * v4 method to resolve individual RunParam.
   *
   * - Handles returning String or Object. (Array is looped in higher level method)
   * - Handles JSONPath tree searching using simple dot notation
   * - Handles resolving multiple param inheritance layers.
   */
  private Object resolveParam(
      ParamType type,
      Object originalValue,
      String wfRunId,
      Layers layers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo) {
    // Case-insensitive matching (ruled 2026-08-26): $(params.myparam) resolves a param declared
    // MyParam. The GitHub Actions model - insensitive lookup paired with the definition-side
    // rejection of case/separator-variant duplicates (ParameterUtil.paramNameCollisions).
    Map<String, Object> flatParamLayers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    flatParamLayers.putAll(layers.paramLayers().getFlatMap());
    if (Objects.isNull(originalValue)) {
      return originalValue;
    }
    // References are discovered over the value's flattened text, which is how a reference nested
    // inside a Map or a List is found at all; substitution then walks the real structure.
    Matcher m = DOT_NOTATION_PATTERN.matcher(originalValue.toString());
    Object resolvedValue = originalValue;
    // An object-typed param resolves to the referenced structure only when its value is exactly
    // one reference and nothing else. Returning on the first match regardless collapsed
    // {"url": "$(params.host)/api", "retries": 3} to the host string - the structure, the "/api"
    // suffix and every other key were dropped and an object silently became a string.
    boolean singleReference = ParamType.object.equals(type) && isSingleReference(originalValue);
    Map<String, Object> foundKeyValues = new HashMap<>();
    while (m.find()) {
      String foundKey = m.group(0);
      String[] separatedKey = foundKey.split("\\.");
      // Dispatch the reference to its shape; the per-shape extraction lives in private helpers.
      Object foundValue = null;
      if ((separatedKey.length == 2) && "params".equalsIgnoreCase(separatedKey[0])) {
        // params.<name>
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 2) && "params".equalsIgnoreCase(separatedKey[0])) {
        // params.<name>.<jsonpath> - query into a child of an object param
        foundValue = objectPathValue(foundKey, 2, flatParamLayers);
      } else if ((separatedKey.length == 3)
          && "params".equalsIgnoreCase(separatedKey[1])
          && isReservedScope(separatedKey[0])) {
        // <scope>.params.<name>
        warnIfDeprecatedScope(separatedKey[0], foundKey);
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 3)
          && "params".equalsIgnoreCase(separatedKey[1])
          && isReservedScope(separatedKey[0])) {
        // <scope>.params.<name>.<jsonpath>
        warnIfDeprecatedScope(separatedKey[0], foundKey);
        foundValue = objectPathValue(foundKey, 3, flatParamLayers);
      } else if ((separatedKey.length >= 4)
          && "tasks".equalsIgnoreCase(separatedKey[0])
          && "results".equalsIgnoreCase(separatedKey[2])) {
        // tasks.<name>.results.<result>[.<jsonpath>]
        foundValue = taskResultValue(foundKey, separatedKey, wfRunId, taskRunMemo, originalValue);
      }
      if (!Objects.isNull(foundValue)) {
        if (singleReference) {
          return foundValue;
        }
        LOGGER.debug("Pattern Matched: " + foundKey + " = " + foundValue.toString());
        foundKeyValues.put(foundKey, foundValue);
      }
    }
    if (!foundKeyValues.isEmpty()) {
      flatParamLayers.putAll(foundKeyValues);
      resolvedValue =
          replaceStringInObject(resolvedValue, flatParamLayers, key -> insertedAsWritten(key, layers));
    }
    LOGGER.debug("Resolved Value: " + resolvedValue);
    return resolvedValue;
  }

  /*
   * Split foundKey at the nth dot: everything before is the flat-map key, everything after is a
   * JSONPath into that key's (object) value. Null when the key is absent.
   */
  private Object objectPathValue(
      String foundKey, int dotOrdinal, Map<String, Object> flatParamLayers) {
    int index = ordinalIndexOf(foundKey, ".", dotOrdinal);
    String searchKey = foundKey.substring(0, index);
    String searchPath = foundKey.substring(index + 1);
    return flatParamLayers.get(searchKey) != null
        ? reduceObjectByJsonPath(searchPath, flatParamLayers.get(searchKey))
        : null;
  }

  /*
   * tasks.<name>.results.<result> with an optional trailing JSONPath. A missing task/result yields
   * null (verbatim passthrough); a trailing path that matches nothing falls back to the original
   * value (preserved v4 behaviour). The upstream TaskRun lookup is memoised for the resolution.
   */
  private Object taskResultValue(
      String foundKey,
      String[] separatedKey,
      String wfRunId,
      Map<String, Optional<TaskRunEntity>> taskRunMemo,
      Object originalValue) {
    String taskName = separatedKey[1];
    String resultName = separatedKey[3];
    Optional<TaskRunEntity> taskRunEntity =
        taskRunMemo.computeIfAbsent(
            taskName, tn -> taskRunRepository.findFirstByNameAndWorkflowRunRef(tn, wfRunId));
    if (taskRunEntity.isEmpty() || taskRunEntity.get().getResults().isEmpty()) {
      return null;
    }
    Optional<RunResult> result =
        taskRunEntity.get().getResults().stream()
            .filter(p -> resultName.equals(p.getName()))
            .findFirst();
    if (result.isEmpty()) {
      return null;
    }
    if (separatedKey.length > 4) {
      int index = ordinalIndexOf(foundKey, ".", 4);
      String searchPath = foundKey.substring(index + 1);
      Object reducedValue = reduceObjectByJsonPath(searchPath, result.get().getValue());
      return reducedValue != null ? reducedValue : originalValue;
    }
    return result.get().getValue();
  }

  /*
   * Whether a value is a String whose whole content is one reference - "$(params.config)" and
   * nothing else. Tested with the same pattern the discovery loop uses: exactly one match, opening
   * on the first character (the lookbehind guarantees the two characters before a match are "$(")
   * and closing on the last (the lookahead guarantees the character after a match is ")").
   *
   * Surrounding whitespace is ignored. A structure cannot carry leading or trailing spaces, so
   * trimming loses nothing, whereas counting them as surrounding text would turn
   * " $(params.config)" into a JSON string - the silent object-to-string change this guard exists
   * to prevent.
   */
  private static boolean isSingleReference(Object value) {
    if (!(value instanceof String string)) {
      return false;
    }
    String trimmed = string.trim();
    Matcher matcher = DOT_NOTATION_PATTERN.matcher(trimmed);
    return matcher.find()
        && matcher.start() == 2
        && matcher.end() == trimmed.length() - 1
        && !matcher.find();
  }

  private void warnIfDeprecatedScope(String scope, String reference) {
    if (ParamLayers.DEPRECATED_WORKSPACE_SCOPE.equalsIgnoreCase(scope)
        && warnedDeprecatedReferences.add(reference)) {
      LOGGER.warn(
          "$({}) uses the deprecated team scope; use $(workspace.{}) instead. team.params is removed in the next major version.",
          reference,
          reference.substring(scope.length() + 1));
    }
  }

  private boolean isReservedScope(String scope) {
    // Case-insensitive like the rest of reference matching (ruled 2026-08-26).
    return List.of(reservedScope).stream().anyMatch(s -> s.equalsIgnoreCase(scope));
  }

  /*
   * Substitute $(...) references into the string leaves of a value.
   *
   * Strings are substituted directly. This method used to JSON-encode the whole value first,
   * splice each replacement's raw toString() into that encoded text, and re-parse it - so any
   * replacement carrying a double quote or a newline made the re-parse fail and the parameter
   * resolved to null (boomerang-io/flow#439). In a string leaf, quotes and newlines are not
   * special, so there is nothing to escape and nothing to re-parse.
   *
   * A replacement that is not a String and is interpolated INTO a larger string is rendered as
   * JSON, the same encoding the dispatcher gives a non-string param on its way into a container -
   * not Java's Map.toString() ("{k=v}"), which nothing downstream can read. A reference that is
   * the whole value of an object-typed param never reaches here: resolveParam returns the
   * referenced structure itself. An object-typed param whose value CONTAINS references does reach
   * here, and its Map and Collection are walked.
   *
   * Any unexpected failure returns the value unchanged - verbatim passthrough, the same fallback
   * an unmatched reference takes - rather than null, which surfaces components away as a missing
   * parameter. The log names the shape of the value and never its content, because a
   * password-typed param flows through here.
   */
  private Object replaceStringInObject(
      Object object, Map<String, Object> replacements, Predicate<String> insertedAsWritten) {
    try {
      StringSubstitutor substitutor =
          new StringSubstitutor(
              (StringLookup)
                  key -> {
                    String replacement = renderReplacement(replacements.get(key));
                    return replacement != null && insertedAsWritten.test(key)
                        ? escapeReferences(replacement)
                        : replacement;
                  },
              "$(",
              ")",
              StringSubstitutor.DEFAULT_ESCAPE);
      substitutor.setEnableSubstitutionInVariables(true);
      substitutor.setEnableUndefinedVariableException(false);
      return substituteInLeaves(object, substitutor, 0);
    } catch (Exception e) {
      // The workflow continues; the value is passed through untouched. The exception message can
      // quote the value, so only its type is logged.
      LOGGER.error(
          "Parameter substitution failed ({}) on a value of shape {}; the original value is used unchanged.",
          e.getClass().getName(),
          describeShape(object));
      return object;
    }
  }

  /*
   * Walk a value's structure and substitute only its string leaves. Depth-guarded so a
   * pathological structure cannot exhaust the stack; a Map's keys are substituted too, matching
   * the whole-document substitution this replaced.
   */
  private Object substituteInLeaves(Object value, StringSubstitutor substitutor, int depth) {
    if (value instanceof String string) {
      return substitutor.replace(string);
    }
    if (value == null) {
      return null;
    }
    if (!isStructured(value)) {
      // A number, a boolean or anything else has no string leaf to substitute into.
      return value;
    }
    if (depth >= MAX_SUBSTITUTION_DEPTH) {
      LOGGER.warn(
          "Parameter substitution stopped at depth {} on a value of shape {}.",
          depth,
          describeShape(value));
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> substituted = new LinkedHashMap<>();
      map.forEach(
          (key, entry) ->
              substituted.put(
                  key instanceof String stringKey ? substitutor.replace(stringKey) : key,
                  substituteInLeaves(entry, substitutor, depth + 1)));
      return substituted;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> substituted = new ArrayList<>(collection.size());
      collection.forEach(
          entry -> substituted.add(substituteInLeaves(entry, substitutor, depth + 1)));
      return substituted;
    }
    // An array becomes a List, which is what the JSON round trip this replaced also produced.
    int length = Array.getLength(value);
    List<Object> substituted = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      substituted.add(substituteInLeaves(Array.get(value, i), substitutor, depth + 1));
    }
    return substituted;
  }

  /*
   * How a replacement is written into a surrounding string. Absent is null, which leaves the
   * reference verbatim.
   */
  private String renderReplacement(Object value) {
    if (value == null || value instanceof String) {
      return (String) value;
    }
    if (isStructured(value)) {
      try {
        return objectMapper.writeValueAsString(value);
      } catch (Exception e) {
        LOGGER.error(
            "Could not render a {} replacement as JSON ({}); its toString() is used.",
            value.getClass().getName(),
            e.getClass().getName());
        return value.toString();
      }
    }
    return value.toString();
  }

  private static boolean isStructured(Object value) {
    return value instanceof Map || value instanceof Collection || value.getClass().isArray();
  }

  /*
   * A value's shape for logging: type and size, never content - a password-typed param resolves
   * through this class.
   */
  private static String describeShape(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String string) {
      return "string[" + string.length() + " chars]";
    }
    if (value instanceof Map<?, ?> map) {
      return "object[" + map.size() + " entries]";
    }
    if (value instanceof Collection<?> collection) {
      return "array[" + collection.size() + " elements]";
    }
    return value.getClass().getName();
  }

  private Object reduceObjectByJsonPath(String path, Object object) {
    // Configuration jsonConfig = Configuration.builder().mappingProvider(new
    // JacksonMappingProvider())
    // .jsonProvider(new JacksonJsonNodeJsonProvider()).options(Option.DEFAULT_PATH_LEAF_TO_NULL)
    // .build();

    Configuration jsonConfig =
        Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);
    try {
      // ObjectMapper mapper = new ObjectMapper();
      // try {
      // String objectString = mapper.writeValueAsString(p.getValue());
      // String replacedObjectString =
      // replacePropertiesAlternate(objectString, wfRunId, paramLayers);
      // p.setValue(mapper.readValue(replacedObjectString, Object.class));

      // String json = object instanceof String ? new JsonObject(object.toString()) : new
      // ObjectMapper().writeValueAsString(object);
      DocumentContext jsonContext = JsonPath.using(jsonConfig).parse(object);
      if (path != null && !path.isBlank() && object != null) {
        Object value = jsonContext.read("$." + path);
        // return value.toString().replaceAll("^\"+|\"+$", "");
        return value;
      }
    } catch (Exception e) {
      // Log and drop exception. We want the workflow to continue execution.
      LOGGER.error(e.toString());
    }
    return null;
  }

  private static int ordinalIndexOf(String str, String substr, int n) {
    int pos = str.indexOf(substr);
    while (--n > 0 && pos != -1) pos = str.indexOf(substr, pos + 1);
    return pos;
  }

}
