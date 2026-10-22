package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/*
 * Holds the Param Layers to be built up and resolved
 * 
 * CAUTION: tightly coupled between Engine and Workflow services
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParamLayers {
  private static final Logger LOGGER = LogManager.getLogger();

  @JsonIgnore
  private Map<String, Object> contextParams = new HashMap<>();

  @JsonIgnore
  private Map<String, Object> globalParams = new HashMap<>();

  @JsonIgnore
  private Map<String, Object> teamParams = new HashMap<>();

  @JsonIgnore
  private Map<String, Object> workflowParams = new HashMap<>();

  @JsonIgnore
  private Map<String, Object> taskParams = new HashMap<>();

  @JsonIgnore
  public Map<String, Object> getTaskParams() {
    return taskParams;
  }

  public void setTaskParams(Map<String, Object> taskInputProperties) {
    this.taskParams = taskInputProperties;
  }

  public Map<String, Object> getGlobalParams() {
    return globalParams;
  }

  public void setGlobalParams(Map<String, Object> globalProperties) {
    this.globalParams = globalProperties;
  }

  public Map<String, Object> getContextParams() {
    return contextParams;
  }

  public void setContextParams(Map<String, Object> contextParams) {
    this.contextParams = contextParams;
  }

  public Map<String, Object> getTeamParams() {
    return teamParams;
  }

  public void setTeamParams(Map<String, Object> teamProperties) {
    this.teamParams = teamProperties;
  }

  public Map<String, Object> getWorkflowParams() {
    return workflowParams;
  }

  public void setWorkflowParams(Map<String, Object> workflowProperties) {
    this.workflowParams = workflowProperties;
  }

  @JsonAnyGetter
  public Map<String, Object> getFlatMap() {

    Map<String, Object> finalProperties = new TreeMap<>();
    copyFlatParams(globalParams, finalProperties, "global");
    copyFlatParams(teamParams, finalProperties, "team");
    copyFlatParams(workflowParams, finalProperties, "workflow");
    copyFlatParams(taskParams, finalProperties, null);
    copyFlatParams(contextParams, finalProperties, "context");

    return finalProperties;
  }

  private void copyFlatParams(Map<String, Object> source, Map<String, Object> target, String prefix) {
    for (Entry<String, Object> entry : source.entrySet()) {
      LOGGER.debug("Parameter: " + entry.toString());
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value != null) {
        if (prefix != null) {
          target.put(prefix + ".params." + key, value);
        }
        target.put("params." + key, value);
      }
    }
  }

  @JsonAnyGetter
  public List<String> getFlatKeys() {
    HashSet<String> keys = new HashSet<>();
    copyFlatKeys(globalParams, keys, "global");
    copyFlatKeys(teamParams, keys, "team");
    copyFlatKeys(workflowParams, keys, "workflow");
    copyFlatKeys(taskParams, keys, null);
    copyFlatKeys(contextParams, keys, "context");

    return new ArrayList<String>(keys);
  }

  private void copyFlatKeys(Map<String, Object> source, HashSet<String> target, String prefix) {
    for (Entry<String, Object> entry : source.entrySet()) {
      LOGGER.debug("Parameter: " + entry.toString());
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value != null) {
        if (prefix != null) {
          target.add(prefix + ".params." + key);
        }
        target.add("params." + key);
      }
    }
  }

  @Override
  public String toString() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    String jsonResult;
    try {
      jsonResult = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    } catch (JsonProcessingException e) {
      return "";
    }
    return jsonResult;
  }

  public Map<String, Object> getMapForKey(String key) {
    if ("workflow".equals(key)) {
      return this.getWorkflowParams();
    } else if ("context".equals(key)) {
      return this.getContextParams();
    } else if ("team".equals(key)) {
      return this.getTeamParams();
    } else if ("global".equals(key)) {
      return this.getGlobalParams();
    } else {
      return new HashMap<>();
    }
  }
}
