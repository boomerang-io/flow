package io.boomerang.common.util;

import io.boomerang.common.enums.ConfigType;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class ParameterUtil {

  /*
   * Add a parameter to an existing Run Parameter list
   *
   * @param the parameter list
   *
   * @param the new parameter to add
   *
   * @return the parameter list
   */
  public static List<RunParam> paramSpecToRunParam(List<ParamSpec> parameterList) {
    return parameterList.stream()
        .map(
            p ->
                new RunParam(
                    p.getName(),
                    p.getDefaultValue(),
                    p.getType() != null ? p.getType() : ParamType.string))
        .collect(Collectors.toList());
  }

  /*
   * Add a parameter to an existing Run Parameter list
   *
   * @param the parameter list
   *
   * @param the new parameter to add
   *
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
   * Add a Run Parameter List to an existing Run Parameter list ensuring unique names
   *
   * @param the parameter list
   *
   * @param the new parameter to add
   *
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
   *
   * @param the new parameter spec to add
   *
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
   * Add a ParamSpec Parameter List to an existing ParamSpec Parameter list ensuring unique names
   *
   * @param the parameter list
   *
   * @param the new parameter to add
   *
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
   * Converts a Parameter Map to a Run Parameter List. This allows us to go between the two object
   * types for storing Run Parameters
   *
   * @param the parameter map
   *
   * @return the parameter list
   */
  public static List<RunParam> mapToRunParamList(Map<String, Object> parameterMap) {
    List<RunParam> parameterList = new LinkedList<>();
    if (parameterMap != null) {
      for (Entry<String, Object> entry : parameterMap.entrySet()) {
        String key = entry.getKey();
        ParamType type = ParamType.object;
        if (parameterMap.get(key) instanceof String) {
          type = ParamType.string;
        } else if (parameterMap.get(key) instanceof List) {
          type = ParamType.array;
        }
        RunParam param = new RunParam(key, parameterMap.get(key), type);
        parameterList.add(param);
      }
    }
    return parameterList;
  }

  /*
   * Converts a Run Parameter List to a Parameter Map. This allows us to go between the two object
   * types for storing Run Parameters
   *
   * @param the parameter map
   *
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
   *
   * @param the name of the parameter
   *
   * @return boolean
   */
  public static boolean containsName(List<RunParam> parameterList, String name) {
    return parameterList.stream().anyMatch(p -> name.equals(p.getName()));
  }

  /*
   * Retrieve the value for the matching name in Run Parameter list
   *
   * @param the parameter list
   *
   * @param the name of the parameter
   *
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
   *
   * @param the name of the parameter
   *
   * @return the reduced list
   */
  public static List<RunParam> removeEntry(List<RunParam> parameterList, String name) {
    List<RunParam> reducedParamList = new LinkedList<>();
    reducedParamList =
        parameterList.stream().filter(p -> !name.equals(p.getName())).collect(Collectors.toList());
    return reducedParamList;
  }

//  /*
//   * Turns the AbstractParam used by the UI into ParamSpec used by the Engine and Handlers
//   */
//  public static List<ParamSpec> abstractParamsToParamSpecs(List<AbstractParam> abstractParams) {
//    List<ParamSpec> params = new LinkedList<>();
//    if (abstractParams != null && !abstractParams.isEmpty()) {
//      for (AbstractParam ap : abstractParams) {
//        ParamSpec param = new ParamSpec();
//        param.setName(ap.getKey());
//        param.setDescription(ap.getDescription());
//        switch (ConfigType.getConfigType(ap.getType())) {
//          case MULTISELECT -> param.setType(ParamType.array);
//          case JSON -> param.setType(ParamType.object);
//          default -> param.setType(ParamType.string);
//        }
//        param.setDefaultValue(ap.getDefaultValue());
//        Config config = new Config();
//        BeanUtils.copyProperties(ap, config);
//        param.setConfig(config);
//        params.add(param);
//      }
//      ;
//    }
//    return params;
//  }

  //
  //  // Loop through the newAPs and if of password type with empty defaultValue, retrieve the
  // original
  //  // value
  //  public static List<AbstractParam> mergeAbstractParms(
  //      List<AbstractParam> origAP, List<AbstractParam> newAP) {
  //    if (newAP != null && !newAP.isEmpty()) {
  //      for (AbstractParam ap : newAP) {
  //        if (ap.getType().equals("password") && ap.getDefaultValue().toString().isEmpty()) {
  //          if (origAP.stream().anyMatch(p -> p.getKey().equals(ap.getKey()))) {
  //            ap.setDefaultValue(
  //                origAP.stream()
  //                    .filter(p -> p.getKey().equals(ap.getKey()))
  //                    .findFirst()
  //                    .get()
  //                    .getDefaultValue());
  //          }
  //        }
  //      }
  //    }
  //    return newAP;
  //  }
  //
  //  /*
  //   * Turns the ParamSpec into an AbstractParam for Canvas UI
  //   *
  //   * TODO this loses the type when going back and forth. Need to move the model to use the same
  // ParamSpec instead of AbstractParam
  //   */
  //  public static List<AbstractParam> paramSpecToAbstractParam(List<ParamSpec> paramSpecs) {
  //    List<AbstractParam> params = new LinkedList<>();
  //    if (paramSpecs != null && !paramSpecs.isEmpty()) {
  //      for (ParamSpec ps : paramSpecs) {
  //        AbstractParam ap = new AbstractParam();
  //        BeanUtils.copyProperties(ps.getConfig(), ap);
  //        ap.setKey(ps.getName());
  //        ap.setDefaultValue(ps.getDefaultValue() != null ? ps.getDefaultValue() : null);
  //        ap.setDescription(ps.getDescription());
  //        // Set needed Label
  //        if (Objects.isNull(ap.getLabel()) || ap.getLabel().isBlank()) {
  //          ap.setLabel(ps.getName());
  //        }
  //        params.add(ap);
  //      }
  //    }
  //    return params;
  //  }

  public static ParamType getTektonParamType(String type) {
    switch (ConfigType.getConfigType(type)) {
      case ConfigType.MULTISELECT:
        return ParamType.array;
      case ConfigType.JSON:
        return ParamType.object;
      default:
        return ParamType.string;
    }
  }
}
