package io.boomerang.util;

import static io.boomerang.common.util.ParameterUtil.getTektonParamType;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamSpec;
import io.boomerang.common.model.RunParam;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

public class ParameterUtil {

  /*
   * Add a parameter to an existing Run Parameter list
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunParam> paramSpecToRunParam(List<AbstractParam> parameterList) {
    return parameterList.stream()
        .map(p -> new RunParam(p.getName(), p.getDefaultValue(), getTektonParamType(p.getType())))
        .collect(Collectors.toList());
  }

  /*
   * Add a parameter to an existing Run Parameter list
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunParam> addUniqueParam(List<RunParam> parameterList, RunParam param) {
    if (parameterList.stream().noneMatch(p -> param.getName().equals(p.getName()))) {
      parameterList.add(param);
    } else {
      parameterList.stream()
          .filter(p -> param.getName().equals(p.getName()))
          .findFirst()
          .ifPresent(p -> p.setValue(param.getValue()));
    }
    return parameterList;
  }

  /*
   * Add a Run Parameter List to an existing Run Parameter list
   * ensuring unique names
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunParam> addUniqueParams(
      List<RunParam> origParameterList, List<RunParam> newParameterList) {
    newParameterList.stream()
        .forEach(
            p -> {
              addUniqueParam(origParameterList, p);
            });
    return origParameterList;
  }

  /*
   * Add a parameter to an existing Run Parameter list
   *
   * @param the parameter list
   * @param the new parameter spec to add
   * @return the parameter list
   */
  public static List<ParamSpec> addUniqueParamSpec(List<ParamSpec> parameterList, ParamSpec param) {
    if (parameterList.stream().noneMatch(p -> param.getName().equals(p.getName()))) {
      parameterList.add(param);
    } else {
      parameterList.stream()
          .filter(p -> param.getName().equals(p.getName()))
          .findFirst()
          .ifPresent(
              p -> {
                p.setDefaultValue(param.getDefaultValue());
                p.setDescription(param.getDescription());
                p.setType(param.getType());
              });
    }
    return parameterList;
  }

  /*
   * Add a ParamSpec Parameter List to an existing ParamSpec Parameter list
   * ensuring unique names
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<ParamSpec> addUniqueParamSpecs(
      List<ParamSpec> origParameterList, List<ParamSpec> newParameterList) {
    newParameterList.stream()
        .forEach(
            p -> {
              addUniqueParamSpec(origParameterList, p);
            });
    return origParameterList;
  }

  /*
   * Converts a Parameter Map to a Run Parameter List.
   * This allows us to go between the two object types for storing Run Parameters
   *
   * @param the parameter map
   * @return the parameter list
   */
  public static List<RunParam> mapToRunParamList(Map<String, Object> parameterMap) {
    List<RunParam> parameterList = new LinkedList<>();
    if (parameterMap != null) {
      for (Entry<String, Object> entry : parameterMap.entrySet()) {
        String key = entry.getKey();
        RunParam param = new RunParam(key, parameterMap.get(key));
        parameterList.add(param);
      }
    }
    return parameterList;
  }

  /*
   * Converts a Run Parameter List to a Parameter Map.
   * This allows us to go between the two object types for storing Run Parameters
   *
   * @param the parameter map
   * @return the parameter list
   */
  public static Map<String, Object> runParamListToMap(List<RunParam> parameterList) {
    Map<String, Object> parameterMap = new HashMap<>();
    if (parameterList != null) {
      parameterList.stream()
          .forEach(
              p -> {
                parameterMap.put(p.getName(), p.getValue());
              });
    }
    return parameterMap;
  }

  /*
   * Checks the Run Parameter list for a matching name
   *
   * @param the parameter list
   * @param the name of the parameter
   * @return boolean
   */
  public static boolean containsName(List<RunParam> parameterList, String name) {
    return parameterList.stream().anyMatch(p -> name.equals(p.getName()));
  }

  /*
   * Retrieve the value for the matching name in Run Parameter list
   *
   * @param the parameter list
   * @param the name of the parameter
   * @return the value
   */
  public static Object getValue(List<RunParam> parameterList, String name) {
    Object value = null;
    Optional<RunParam> param =
        parameterList.stream().filter(p -> name.equals(p.getName())).findFirst();
    if (param.isPresent()) {
      value = param.get().getValue();
    }
    return value;
  }

  /*
   * Remove the entry for the matching name in Run Parameter list
   *
   * @param the parameter list
   * @param the name of the parameter
   * @return the reduced list
   */
  public static List<RunParam> removeEntry(List<RunParam> parameterList, String name) {
    List<RunParam> reducedParamList = new LinkedList<>();
    reducedParamList =
        parameterList.stream().filter(p -> !name.equals(p.getName())).collect(Collectors.toList());
    return reducedParamList;
  }
}
