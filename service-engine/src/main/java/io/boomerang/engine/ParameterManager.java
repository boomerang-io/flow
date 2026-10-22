package io.boomerang.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.boomerang.client.WorkflowClient;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.util.ParameterUtil;
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

  private static final String REGEX_DOT_NOTATION = "(?<=\\$\\().+?(?=\\))";
  private final String[] reservedScope = {"global", "team", "workflow", "context"};

  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final WorkflowClient workflowClient;

  public ParameterManager(
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      WorkflowClient workflowClient) {
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.workflowClient = workflowClient;
  }

  /*
   * Resolve all RunParams for either WorkflowRun or TaskRun
   */
  public void resolveParamLayers(WorkflowRunEntity wfRun, Optional<TaskRunEntity> optTaskRun) {
    ParamLayers paramLayers = buildParameterLayering(wfRun, optTaskRun);
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
                        paramLayers));
              } else if (ParamType.array.equals(p.getType()) && p.getValue() instanceof List) {
                // Type safety. If you attempt to convert a string or object (JSON = HashMap) then
                // this causes an exception
                ArrayList<String> valueList = (ArrayList<String>) p.getValue();
                p.setValue(
                    valueList.stream()
                        .map(v -> resolveParam(ParamType.string, v, wfRunId, paramLayers))
                        .collect(Collectors.toList()));
              } else if (ParamType.object.equals(p.getType())) {
                // Replace Param with Object. Treated as JSON and allows for the extra JSONPath
                // retrieval.
                p.setValue(resolveParam(p.getType(), p.getValue(), wfRunId, paramLayers));
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
    LOGGER.debug("Received Team Params: " + wfRun.getAnnotations().get("boomerang.io/team-params"));
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
      ParamType type, Object originalValue, String wfRunId, ParamLayers paramLayers) {
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
      Object foundValue = null;
      int start = m.start() - 2;
      int end = m.end() + 1;
      String[] separatedKey = foundKey.split("\\.");
      if ((separatedKey.length == 2) && "params".equals(separatedKey[0])) {
        // Handle flattened - params.<name>
        if (flatParamLayers.get(foundKey) != null) {
          foundValue = flatParamLayers.get(foundKey);
        }
      } else if ((separatedKey.length > 2) && "params".equals(separatedKey[0])) {
        // Handle flattened with query for individual child of an object param -
        // params.<name>.<query>
        int index = ordinalIndexOf(foundKey, ".", 2);
        String searchKey = foundKey.substring(0, index);
        String searchPath = foundKey.substring(index + 1);
        if (flatParamLayers.get(searchKey) != null) {
          foundValue = reduceObjectByJsonPath(searchPath, flatParamLayers.get(searchKey));
        }
      } else if ((separatedKey.length == 3)
          && "params".equals(separatedKey[1])
          && List.of(reservedScope).contains(separatedKey[0])) {
        // Handle specific scoped param - <scope>.params.<name>
        foundValue = flatParamLayers.get(foundKey);
      } else if ((separatedKey.length > 3)
          && "params".equals(separatedKey[1])
          && List.of(reservedScope).contains(separatedKey[0])) {
        // Hanlde specific scoped param with query for child of object -
        // <scope>.params.<name>.<query>
        int index = ordinalIndexOf(foundKey, ".", 3);
        String searchKey = foundKey.substring(0, index);
        String searchPath = foundKey.substring(index + 1);
        if (flatParamLayers.get(searchKey) != null) {
          foundValue = reduceObjectByJsonPath(searchPath, flatParamLayers.get(searchKey));
        }
      } else if ((separatedKey.length >= 4)
          && "tasks".equals(separatedKey[0])
          && "results".equals(separatedKey[2])) {
        // Handle references to TaskRun Results
        String taskName = separatedKey[1];
        String resultName = separatedKey[3];
        Optional<TaskRunEntity> taskRunEntity =
            taskRunRepository.findFirstByNameAndWorkflowRunRef(taskName, wfRunId);
        if (taskRunEntity.isPresent()) {
          List<RunResult> taskRunResults = taskRunEntity.get().getResults();
          if (!taskRunResults.isEmpty()) {
            Optional<RunResult> result =
                taskRunResults.stream().filter(p -> resultName.equals(p.getName())).findFirst();
            if (result.isPresent()) {
              if (separatedKey.length > 4) {
                int index = ordinalIndexOf(foundKey, ".", 4);
                String searchPath = foundKey.substring(index + 1);
                Object reducedValue = reduceObjectByJsonPath(searchPath, result.get().getValue());
                foundValue = reducedValue != null ? reducedValue : originalValue;
              } else {
                foundValue = result.get().getValue();
              }
            }
          }
        }
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

  private Object replaceStringInObject(Object object, Map<String, Object> replacements) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      String objectString = mapper.writeValueAsString(object);
      // objectString.replaceAll(replaceKey, replaceValueString);
      final StringSubstitutor substitutor = new StringSubstitutor(replacements, "$(", ")");
      substitutor.setEnableSubstitutionInVariables(true);
      substitutor.setEnableUndefinedVariableException(false);
      // return substitutor.replace(objectString);
      String replacedObjectString = substitutor.replace(objectString);
      LOGGER.debug("Substitutor: " + replacedObjectString);
      return mapper.readValue(replacedObjectString, Object.class);
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

  /*
   * Original v3 method
   */
  //  private String replaceProperties(String value, String activityId,
  //      ControllerRequestProperties applicationProperties) {
  //
  //    Map<String, String> executionProperties = applicationProperties.getMap(true);
  //
  //    String regex = "(?<=\\$\\().+?(?=\\))";
  //    Pattern pattern = Pattern.compile(regex);
  //    Matcher m = pattern.matcher(value);
  //    List<String> originalValues = new LinkedList<>();
  //    List<String> newValues = new LinkedList<>();
  //    while (m.find()) {
  //      String extractedValue = m.group(0);
  //      String replaceValue = null;
  //
  //      int start = m.start() - 2;
  //      int end = m.end() + 1;
  //      String[] components = extractedValue.split("\\.");
  //
  //      if (components.length == 2) {
  //        List<String> reservedList = Arrays.asList(reserved);
  //
  //        String params = components[0];
  //        if ("params".equals(params)) {
  //
  //          String propertyName = components[1];
  //
  //
  //          if (executionProperties.get(propertyName) != null) {
  //            replaceValue = executionProperties.get(propertyName);
  //          } else {
  //            replaceValue = "";
  //          }
  //        } else if (reservedList.contains(params)) {
  //          String key = components[1];
  //          if ("allParams".equals(key)) {
  //            Map<String, String> properties = applicationProperties.getMapForKey(params);
  //            replaceValue = this.getEncodedPropertiesForMap(properties);
  //          }
  //        }
  //      } else if (components.length == 4) {
  //
  //        String task = components[0];
  //        String taskName = components[1];
  //        String results = components[2];
  //        String outputProperty = components[3];
  //
  //        if (("task".equals(task) || "tasks".equals(task)) && "results".equals(results)) {
  //
  //          TaskExecutionEntity taskExecution = getTaskExecutionEntity(activityId, taskName);
  //          if (taskExecution != null && taskExecution.getOutputs() != null
  //              && taskExecution.getOutputs().get(outputProperty) != null) {
  //            replaceValue = taskExecution.getOutputs().get(outputProperty);
  //          } else {
  //            replaceValue = "";
  //          }
  //        }
  //      } else if (components.length == 3) {
  //        String scope = components[0];
  //        String params = components[1];
  //        String name = components[2];
  //        List<String> reservedList = Arrays.asList(reserved);
  //        if ("tokens".equals(params) && "system".equals(scope)) {
  //          if (executionProperties.get(extractedValue) != null) {
  //            replaceValue = executionProperties.get(extractedValue);
  //          } else {
  //            replaceValue = "";
  //          }
  //        } else if ("params".equals(params) && reservedList.contains(scope)) {
  //          if (reservedList.contains(scope)) {
  //            String key = scope + "/" + name;
  //
  //            if (executionProperties.get(key) != null) {
  //              replaceValue = executionProperties.get(key);
  //            } else {
  //              replaceValue = "";
  //            }
  //          }
  //        }
  //      }
  //
  //      if (replaceValue != null) {
  //        String regexStr = value.substring(start, end);
  //        originalValues.add(regexStr);
  //        newValues.add(replaceValue);
  //      }
  //    }
  //
  //    String[] originalValuesArray = originalValues.toArray(new String[originalValues.size()]);
  //    String[] newValuesArray = newValues.toArray(new String[newValues.size()]);
  //    String updatedString = StringUtils.replaceEach(value, originalValuesArray, newValuesArray);
  //    return updatedString;
  //  }

  // private String getEncodedPropertiesForMap(Map<String, String> map) {
  // Properties properties = new Properties();
  //
  // for (Map.Entry<String, String> entry : map.entrySet()) {
  // String originalKey = entry.getKey();
  // String value = entry.getValue();
  // String modifiedKey = originalKey.replaceAll("-", "\\.");
  // properties.put(modifiedKey, value);
  // }
  //
  // try {
  // properties.putAll(map);
  // ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
  // properties.store(outputStream, null);
  // String text = outputStream.toString();
  // String[] lines = text.split("\\n");
  //
  // StringBuilder sb = new StringBuilder();
  // for (String line : lines) {
  // if (!line.startsWith("#")) {
  // sb.append(line + '\n');
  // }
  //
  // }
  // String propertiesFile = sb.toString();
  // String encodedString = Base64.getEncoder().encodeToString(propertiesFile.getBytes());
  // return encodedString;
  // } catch (IOException e) {
  // return "";
  // }
  // }
}
