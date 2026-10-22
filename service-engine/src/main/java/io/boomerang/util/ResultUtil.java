package io.boomerang.util;

import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ResultUtil {

  /*
   * Convert ResultSpec to RunResult
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunResult> resultSpecToRunResult(List<ResultSpec> resultList) {
    if (Objects.isNull(resultList) || resultList.isEmpty()) {
      return new ArrayList<>();
    }
    return resultList.stream()
        .map(
            r -> {
              RunResult result = new RunResult();
              result.setName(r.getName());
              result.setDescription(r.getDescription());
              return result;
            })
        .collect(Collectors.toList());
  }

  /*
   * Add a result to an existing Run Result list
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunResult> addUniqueResult(List<RunResult> origList, RunResult result) {
    if (origList.stream().noneMatch(p -> result.getName().equals(p.getName()))) {
      origList.add(result);
    } else {
      origList.stream()
          .filter(p -> result.getName().equals(p.getName()))
          .findFirst()
          .ifPresent(p -> p.setValue(result.getValue()));
    }
    return origList;
  }

  /*
   * Add a Run Parameter List to an existing Run Parameter list
   * ensuring unique names
   *
   * @param the parameter list
   * @param the new parameter to add
   * @return the parameter list
   */
  public static List<RunResult> addUniqueResults(
      List<RunResult> origList, List<RunResult> newList) {
    newList.stream()
        .forEach(
            r -> {
              addUniqueResult(origList, r);
            });
    return origList;
  }
}
