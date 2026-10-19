package io.boomerang.dispatcher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.model.ClaimFilter;
import io.boomerang.workflow.TaskService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves a task poll's filters to the typed fields the claim query reads. Each filter is a
 * comma-separated list in which {@code *} is the only wildcard, anchored at both ends; a blank
 * filter is no filter. Task slug forms ({@code <slug>} for a global task, {@code
 * <workspace>/<slug>} for a workspace task) resolve to task ids and a workflow label to the ids of
 * the workflows carrying it, cached per selector so the poll's one-second re-check reads no more.
 */
@Service
public class ClaimFilterService {

  // A newly labelled workflow or new task becomes claimable within this long.
  private static final Duration RESOLUTION_TTL = Duration.ofSeconds(30);

  private final MongoTemplate mongoTemplate;

  private final Cache<String, List<String>> resolved =
      Caffeine.newBuilder().expireAfterWrite(RESOLUTION_TTL).maximumSize(1000).build();

  public ClaimFilterService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Resolve the filters of one task poll against the dispatcher's registered types. Throw
   * QUERY_INVALID_FILTERS for a type the dispatcher did not register, a filter with no values, or
   * a workflow label without a {@code key=value} shape.
   */
  public ClaimFilter resolve(
      List<TaskType> registered, String type, String task, String workflowLabel) {
    List<TaskType> types =
        StringUtils.hasText(type) ? registeredTypes(registered, values("type", type)) : registered;
    List<String> taskRefs =
        StringUtils.hasText(task)
            ? cached("task:" + task, () -> taskRefs(values("task", task)))
            : null;
    List<String> workflowRefs =
        StringUtils.hasText(workflowLabel)
            ? cached("workflowLabel:" + workflowLabel, () -> workflowIds(workflowLabel))
            : null;
    return new ClaimFilter(types, taskRefs, workflowRefs);
  }

  private List<String> cached(String key, Supplier<List<String>> resolver) {
    return resolved.get(key, k -> resolver.get());
  }

  private static List<TaskType> registeredTypes(List<TaskType> registered, List<String> values) {
    for (String value : values) {
      if (!value.contains("*") && !registered.contains(TaskType.getType(value))) {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "type");
      }
    }
    List<Pattern> patterns = values.stream().map(v -> Pattern.compile(anchored(v))).toList();
    return registered.stream()
        .filter(t -> patterns.stream().anyMatch(p -> p.matcher(t.getLabel()).matches()))
        .toList();
  }

  // "deploy,ws-a/build*": a bare slug names a global task, `<workspace>/<slug>` that workspace's
  // task, read from the relationship graph where scope lives; the wildcard applies to the slug half.
  private List<String> taskRefs(List<String> values) {
    List<String> refs = new ArrayList<>();
    for (String value : values) {
      int split = value.indexOf(TaskService.SLUG_SEPARATOR);
      refs.addAll(
          (split < 0)
              ? nodeRefs(RelationshipType.TASK, null, value)
              : workspaceTaskRefs(value.substring(0, split), value.substring(split + 1)));
    }
    return refs;
  }

  private List<String> workspaceTaskRefs(String workspace, String slug) {
    Document node =
        mongoTemplate.findOne(
            Query.query(
                Criteria.where("type")
                    .is(RelationshipType.WORKSPACE.getLabel())
                    .orOperator(
                        Criteria.where("slug").is(workspace), Criteria.where("ref").is(workspace))),
            Document.class,
            mongoTemplate.getCollectionName(RelationshipNodeEntity.class));
    if (node == null) {
      return List.of();
    }
    List<String> children =
        mongoTemplate
            .find(
                Query.query(
                    Criteria.where("from")
                        .is(node.get("_id").toString())
                        .and("label")
                        .is(RelationshipLabel.HAS_TASK.getLabel())),
                Document.class,
                mongoTemplate.getCollectionName(RelationshipEdgeEntity.class))
            .stream()
            .map(edge -> edge.getString("to"))
            .toList();
    return children.isEmpty() ? List.of() : nodeRefs(RelationshipType.TEAMTASK, children, slug);
  }

  // The refs of the nodes of one type, among the given ids when any, whose slug matches.
  private List<String> nodeRefs(RelationshipType type, List<String> ids, String slug) {
    Criteria criteria = Criteria.where("type").is(type.getLabel());
    if (ids != null) {
      criteria = criteria.and("_id").in(ids);
    }
    criteria =
        slug.contains("*")
            ? criteria.and("slug").regex(anchored(slug))
            : criteria.and("slug").is(slug);
    Query query = Query.query(criteria);
    query.fields().include("ref");
    return mongoTemplate
        .find(query, Document.class, mongoTemplate.getCollectionName(RelationshipNodeEntity.class))
        .stream()
        .map(document -> document.getString("ref"))
        .toList();
  }

  // "archie.io/workload=knowledge,eval": one key, then the values. A dot in the key is matched in
  // its stored, escaped form, as every label query does.
  private List<String> workflowIds(String workflowLabel) {
    int split = workflowLabel.indexOf('=');
    if (split <= 0) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "workflowLabel");
    }
    String field = "labels." + workflowLabel.substring(0, split).trim().replace(".", "#");
    return ids(
        WorkflowEntity.class, field, values("workflowLabel", workflowLabel.substring(split + 1)));
  }

  private List<String> ids(Class<?> entity, String field, List<String> values) {
    Query query =
        Query.query(
            new Criteria()
                .orOperator(
                    values.stream()
                        .map(
                            v ->
                                v.contains("*")
                                    ? Criteria.where(field).regex(anchored(v))
                                    : Criteria.where(field).is(v))
                        .toList()));
    query.fields().include("_id");
    return mongoTemplate
        .find(query, Document.class, mongoTemplate.getCollectionName(entity))
        .stream()
        .map(document -> document.get("_id").toString())
        .toList();
  }

  private static List<String> values(String filter, String raw) {
    List<String> values =
        Arrays.stream(raw.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    if (values.isEmpty()) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, filter);
    }
    return values;
  }

  /** Return the regular expression for a filter value: literal except {@code *}, anchored. */
  static String anchored(String value) {
    return Arrays.stream(value.split("\\*", -1))
        .map(ClaimFilterService::literal)
        .collect(Collectors.joining(".*", "^", "$"));
  }

  // Backslash-escapes every regular-expression metacharacter, so the same text means the same thing
  // to Java and to MongoDB.
  private static String literal(String text) {
    return text.replaceAll("[\\\\^$.|?+()\\[\\]{}]", "\\\\$0");
  }
}
