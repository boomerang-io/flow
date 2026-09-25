package io.boomerang.engine;

import tools.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
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
import java.util.TreeSet;
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
  private final String[] reservedScope = {"global", "team", "workflow", "context"};

  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final ObjectMapper objectMapper;

  public ParameterManager(
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      ObjectMapper objectMapper) {
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.objectMapper = objectMapper;
  }

  /*
   * The outcome of resolving one value: the value, and whether a reference to a secret was
   * substituted into it. The flag is how a string param inherits the secret type.
   */
  private record Resolution(Object value, boolean secret) {}

  /*
   * Resolve all RunParams for either WorkflowRun or TaskRun
   */
  public void resolveParamLayers(WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    ParamLayers paramLayers = buildParameterLayering(wfRun, optTaskRun);
    Set<String> secretKeys = secretReferenceKeys(wfRun, optTaskRun, paramLayers);
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
              // Name and type only: a secret's value must never reach a log.
              LOGGER.debug(
                  "Resolving Parameter: {} ({})",
                  p.getName(),
                  p.getType() == null ? ParamType.string : p.getType());
              if (ParamType.string.equals(p.getType())
                  || ParamType.secret.equals(p.getType())
                  || p.getType() == null) {
                // Default to String replacement. This also allows recursive use of Params and
                // multiple Param replacement. A secret is a string and resolves the same way.
                Resolution resolution =
                    resolveParam(
                        ParamType.string,
                        p.getValue() != null ? p.getValue().toString() : "",
                        wfRunId,
                        paramLayers,
                        taskRunMemo,
                        secretKeys);
                p.setValue(resolution.value());
                // Taint: a string that now carries a secret's value is itself a secret. Arrays
                // and objects keep their type - a secret is a string only - and rely on the value
                // scrub on the way out (DataAdapterUtil.scrubWorkflowRunValues).
                if (resolution.secret()) {
                  p.setType(ParamType.secret);
                }
              } else if (ParamType.array.equals(p.getType()) && p.getValue() instanceof List) {
                // Type safety. If you attempt to convert a string or object (JSON = HashMap) then
                // this causes an exception
                ArrayList<String> valueList = (ArrayList<String>) p.getValue();
                p.setValue(
                    valueList.stream()
                        .map(
                            v ->
                                resolveParam(
                                        ParamType.string,
                                        v,
                                        wfRunId,
                                        paramLayers,
                                        taskRunMemo,
                                        secretKeys)
                                    .value())
                        .collect(Collectors.toList()));
              } else if (ParamType.object.equals(p.getType())) {
                // Replace Param with Object. Treated as JSON and allows for the extra JSONPath
                // retrieval.
                p.setValue(
                    resolveParam(
                            p.getType(),
                            p.getValue(),
                            wfRunId,
                            paramLayers,
                            taskRunMemo,
                            secretKeys)
                        .value());
              }
            });
    // Return WorkflowRun or TaskRun RunParams
    if (optTaskRun.isPresent()) {
      optTaskRun.get().setParams(runParams);
      // The spec is part of the contract too: $(params.x) in script/command/arguments/envs must
      // resolve identically on every executor, so it happens here rather than relying on
      // Tekton's controller-side substitution (which Kubernetes Jobs and Docker do not have).
      resolveSpec(
          optTaskRun.get().getSpec(), wfRun.getId(), paramLayers, taskRunMemo, secretKeys);
    } else {
      wfRun.setParams(runParams);
    }
  }

  /*
   * Resolve $(params.x) references inside the TaskRun spec's string fields. Same one-pass
   * semantics as a param value referencing another param; unresolved references are left as-is.
   */
  private void resolveSpec(
      TaskRunSpec spec,
      String wfRunId,
      ParamLayers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo,
      Set<String> secretKeys) {
    if (spec == null) {
      return;
    }
    spec.setScript(resolveString(spec.getScript(), wfRunId, paramLayers, taskRunMemo, secretKeys));
    spec.setCommand(
        resolveStrings(spec.getCommand(), wfRunId, paramLayers, taskRunMemo, secretKeys));
    spec.setArguments(
        resolveStrings(spec.getArguments(), wfRunId, paramLayers, taskRunMemo, secretKeys));
    if (spec.getEnvs() != null) {
      spec.getEnvs()
          .forEach(
              env ->
                  env.setValue(
                      resolveString(
                          env.getValue(), wfRunId, paramLayers, taskRunMemo, secretKeys)));
    }
  }

  // Spec fields are not params and carry no type; a secret substituted into one is covered by
  // the value scrub on the way out, so the taint flag is not needed here.
  private String resolveString(
      String value,
      String wfRunId,
      ParamLayers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo,
      Set<String> secretKeys) {
    if (value == null) {
      return null;
    }
    Object resolved =
        resolveParam(ParamType.string, value, wfRunId, paramLayers, taskRunMemo, secretKeys)
            .value();
    return resolved != null ? resolved.toString() : null;
  }

  private List<String> resolveStrings(
      List<String> values,
      String wfRunId,
      ParamLayers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo,
      Set<String> secretKeys) {
    if (values == null) {
      return null;
    }
    return values.stream()
        .map(v -> resolveString(v, wfRunId, paramLayers, taskRunMemo, secretKeys))
        .collect(Collectors.toList());
  }

  /*
   * The flat reference keys whose value is a secret, following the same precedence
   * ParamLayers.getFlatMap applies to the values: global, workspace, workflow, task, then context,
   * each later layer winning `params.<name>`, and a null value never written (so never winning).
   * Only the workflow and task layers carry a type; the global and workspace layers are value maps
   * with no type, so a reference to one of those is not a secret here (the display filter's name
   * join and value scrub still apply to the definitions that declare them).
   */
  private static Set<String> secretReferenceKeys(
      WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun, ParamLayers paramLayers) {
    Map<String, Boolean> secretByKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    markSecretKeys(secretByKey, wfRun.getParams(), "workflow");
    optTaskRun.ifPresent(taskRun -> markSecretKeys(secretByKey, taskRun.getParams(), null));
    paramLayers.getContextParams().forEach(
        (name, value) -> {
          if (value != null) {
            secretByKey.put("params." + name, false);
          }
        });
    Set<String> secretKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    secretByKey.forEach(
        (key, secret) -> {
          if (secret) {
            secretKeys.add(key);
          }
        });
    return secretKeys;
  }

  private static void markSecretKeys(
      Map<String, Boolean> secretByKey, List<RunParam> params, String prefix) {
    if (params == null) {
      return;
    }
    for (RunParam p : params) {
      if (p.getName() == null || p.getValue() == null) {
        continue;
      }
      boolean secret = ParamType.secret.equals(p.getType());
      if (prefix != null) {
        secretByKey.put(prefix + ".params." + p.getName(), secret);
      }
      secretByKey.put("params." + p.getName(), secret);
    }
  }

  /*
   * Build all parameter layers as an object of Maps
   *
   * If you only pass it the Workflow Run Entity, it won't add the Task Run Params to the map
   */
  private ParamLayers buildParameterLayering(
      WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    ParamLayers paramLayers = new ParamLayers();

    LOGGER.debug(
        "Received Global Params: " + wfRun.getAnnotations().get("boomerang.io/global-params"));
    LOGGER.debug("Received Workspace Params: " + wfRun.getAnnotations().get("boomerang.io/workspace-params"));
    LOGGER.debug(
        "Received Context Params: " + wfRun.getAnnotations().get("boomerang.io/context-params"));

    if (wfRun.getAnnotations().containsKey("boomerang.io/workspace-params")
        && wfRun.getAnnotations().get("boomerang.io/workspace-params") != null) {
      paramLayers.setTeamParams(
          (Map<String, Object>) wfRun.getAnnotations().get("boomerang.io/workspace-params"));
    }
    if (wfRun.getAnnotations().containsKey("boomerang.io/global-params")
        && wfRun.getAnnotations().get("boomerang.io/global-params") != null) {
      paramLayers.setGlobalParams(
          (Map<String, Object>) wfRun.getAnnotations().get("boomerang.io/global-params"));
    }
    if (wfRun.getAnnotations().containsKey("boomerang.io/context-params")
        && wfRun.getAnnotations().get("boomerang.io/context-params") != null) {
      paramLayers.setContextParams(
          (Map<String, Object>) wfRun.getAnnotations().get("boomerang.io/context-params"));
    }

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

    return paramLayers;
  }

  /*
   * v4 method to resolve individual RunParam.
   *
   * - Handles returning String or Object. (Array is looped in higher level method)
   * - Handles JSONPath tree searching using simple dot notation
   * - Handles resolving multiple param inheritance layers.
   */
  private Resolution resolveParam(
      ParamType type,
      Object originalValue,
      String wfRunId,
      ParamLayers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo,
      Set<String> secretKeys) {
    // Case-insensitive matching (ruled 2026-08-26): $(params.myparam) resolves a param declared
    // MyParam. The GitHub Actions model - insensitive lookup paired with the definition-side
    // rejection of case/separator-variant duplicates (ParameterUtil.paramNameCollisions).
    Map<String, Object> flatParamLayers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    flatParamLayers.putAll(paramLayers.getFlatMap());
    if (Objects.isNull(originalValue)) {
      return new Resolution(originalValue, false);
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
    // Set where a reference is known to have resolved: whether any of them named a secret.
    boolean secret = false;
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
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 3)
          && "params".equalsIgnoreCase(separatedKey[1])
          && isReservedScope(separatedKey[0])) {
        // <scope>.params.<name>.<jsonpath>
        foundValue = objectPathValue(foundKey, 3, flatParamLayers);
      } else if ((separatedKey.length >= 4)
          && "tasks".equalsIgnoreCase(separatedKey[0])
          && "results".equalsIgnoreCase(separatedKey[2])) {
        // tasks.<name>.results.<result>[.<jsonpath>]
        foundValue = taskResultValue(foundKey, separatedKey, wfRunId, taskRunMemo, originalValue);
      }
      if (!Objects.isNull(foundValue)) {
        boolean foundSecret = secretKeys.contains(foundKey);
        if (singleReference) {
          return new Resolution(foundValue, foundSecret);
        }
        secret |= foundSecret;
        // The key only: the value may be a secret.
        LOGGER.debug("Pattern Matched: {}", foundKey);
        foundKeyValues.put(foundKey, foundValue);
      }
    }
    if (!foundKeyValues.isEmpty()) {
      flatParamLayers.putAll(foundKeyValues);
      resolvedValue = replaceStringInObject(resolvedValue, flatParamLayers);
    }
    return new Resolution(resolvedValue, secret);
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
  private Object replaceStringInObject(Object object, Map<String, Object> replacements) {
    try {
      StringSubstitutor substitutor =
          new StringSubstitutor(
              (StringLookup) key -> renderReplacement(replacements.get(key)),
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
