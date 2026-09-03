package io.boomerang.engine;

import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class ResultUtil {

  /*
   * Upserts one result by name on a stored run document without a read-modify-write, so a result
   * key stays unique (issue #241) and a concurrent writer of a different key is never lost.
   *
   * Two guarded field-scoped writes: a positional $set on the element with that name; when none
   * matches, a $push guarded by "results.name" $ne name so two first writers of the same key
   * cannot both insert. The guard failing means the other writer's element now exists, so the
   * positional $set is retried. Bounded, and one round trip in the common case.
   */
  public static void upsertResultByName(
      MongoTemplate mongoTemplate, String id, Class<?> entityClass, RunResult result) {
    for (int attempt = 0; attempt < 3; attempt++) {
      Query existing =
          Query.query(Criteria.where("_id").is(id).and("results.name").is(result.getName()));
      Update setValue = new Update().set("results.$.value", result.getValue());
      if (mongoTemplate.updateFirst(existing, setValue, entityClass).getMatchedCount() > 0) {
        return;
      }
      Query absent =
          Query.query(Criteria.where("_id").is(id).and("results.name").ne(result.getName()));
      if (mongoTemplate
              .updateFirst(absent, new Update().push("results", result), entityClass)
              .getMatchedCount()
          > 0) {
        return;
      }
    }
  }

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
