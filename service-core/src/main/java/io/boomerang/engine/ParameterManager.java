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
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.text.StringSubstitutor;
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
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REGEX_DOT_NOTATION = "(?<=\\$\\().+?(?=\\))";
  private final String[] reservedScope = {"global", "team", "workflow", "context"};

  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;

  public ParameterManager(
      WorkflowRunRepository workflowRunRepository, TaskRunRepository taskRunRepository) {
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
  }

  /*
   * Resolve all RunParams for either WorkflowRun or TaskRun
   */
  public void resolveParamLayers(WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    ParamLayers paramLayers = buildParameterLayering(wfRun, optTaskRun);
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
    } else {
      wfRun.setParams(runParams);
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
    LOGGER.debug("Received Workspace Params: " + wfRun.getAnnotations().get("boomerang.io/team-params"));
    LOGGER.debug(
        "Received Context Params: " + wfRun.getAnnotations().get("boomerang.io/context-params"));

    if (wfRun.getAnnotations().containsKey("boomerang.io/team-params")
        && wfRun.getAnnotations().get("boomerang.io/team-params") != null) {
      paramLayers.setTeamParams(
          (Map<String, Object>) wfRun.getAnnotations().get("boomerang.io/team-params"));
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
  private Object resolveParam(
      ParamType type,
      Object originalValue,
      String wfRunId,
      ParamLayers paramLayers,
      Map<String, Optional<TaskRunEntity>> taskRunMemo) {
    Map<String, Object> flatParamLayers = paramLayers.getFlatMap();
    Pattern pattern = Pattern.compile(REGEX_DOT_NOTATION);
    if (Objects.isNull(originalValue)) {
      return originalValue;
    }
    Matcher m = pattern.matcher(originalValue.toString());
    Object resolvedValue = originalValue;
    Map<String, Object> foundKeyValues = new HashMap<>();
    while (m.find()) {
      String foundKey = m.group(0);
      String[] separatedKey = foundKey.split("\\.");
      // Dispatch the reference to its shape; the per-shape extraction lives in private helpers.
      Object foundValue = null;
      if ((separatedKey.length == 2) && "params".equals(separatedKey[0])) {
        // params.<name>
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 2) && "params".equals(separatedKey[0])) {
        // params.<name>.<jsonpath> - query into a child of an object param
        foundValue = objectPathValue(foundKey, 2, flatParamLayers);
      } else if ((separatedKey.length == 3)
          && "params".equals(separatedKey[1])
          && isReservedScope(separatedKey[0])) {
        // <scope>.params.<name>
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 3)
          && "params".equals(separatedKey[1])
          && isReservedScope(separatedKey[0])) {
        // <scope>.params.<name>.<jsonpath>
        foundValue = objectPathValue(foundKey, 3, flatParamLayers);
      } else if ((separatedKey.length >= 4)
          && "tasks".equals(separatedKey[0])
          && "results".equals(separatedKey[2])) {
        // tasks.<name>.results.<result>[.<jsonpath>]
        foundValue = taskResultValue(foundKey, separatedKey, wfRunId, taskRunMemo, originalValue);
      }
      if (!Objects.isNull(foundValue)) {
        if (ParamType.object.equals(type)) {
          return foundValue;
        } else {
          LOGGER.debug("Pattern Matched: " + foundKey + " = " + foundValue.toString());
          foundKeyValues.put(foundKey, foundValue);
        }
      }
    }
    if (!foundKeyValues.isEmpty()) {
      flatParamLayers.putAll(foundKeyValues);
      resolvedValue = replaceStringInObject(resolvedValue, flatParamLayers);
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

  private boolean isReservedScope(String scope) {
    return List.of(reservedScope).contains(scope);
  }

  private Object replaceStringInObject(Object object, Map<String, Object> replacements) {
    try {
      String objectString = OBJECT_MAPPER.writeValueAsString(object);
      // objectString.replaceAll(replaceKey, replaceValueString);
      final StringSubstitutor substitutor = new StringSubstitutor(replacements, "$(", ")");
      substitutor.setEnableSubstitutionInVariables(true);
      substitutor.setEnableUndefinedVariableException(false);
      // return substitutor.replace(objectString);
      String replacedObjectString = substitutor.replace(objectString);
      LOGGER.debug("Substitutor: " + replacedObjectString);
      return OBJECT_MAPPER.readValue(replacedObjectString, Object.class);
    } catch (Exception e) {
      // Log and drop exception. We want the workflow to continue execution.
      LOGGER.error(e.toString());
    }
    return null;
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
