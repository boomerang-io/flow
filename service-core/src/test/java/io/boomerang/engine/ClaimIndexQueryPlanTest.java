package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoCollection;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.model.ClaimFilter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The query plans of the queries on {@code claim_page}, against task-run history: the termination
 * check reads only the task runs a dispatcher still holds, not every cancelled task run of the last
 * seven days, and the claim page keeps an index-ordered plan.
 */
class ClaimIndexQueryPlanTest extends AbstractEngineIntegrationTest {

  private static final int RELEASED = 2_000;
  private static final int HELD = 3;
  private static final ClaimFilter TEMPLATES = ClaimFilter.of(List.of(TaskType.template));

  @Autowired private MongoTemplate mongoTemplate;

  // The held task runs would otherwise be handed to other tests' dispatchers as termination orders.
  @AfterEach
  void removeTheHistory() {
    collection().deleteMany(new Document("workflowRunRef", "claim-index-plan-run"));
  }

  @Test
  void theTerminationCheckReadsOnlyHeldTaskRuns() {
    // History: many cancelled task runs whose claims were released, and a few still held.
    List<Document> history = new ArrayList<>();
    Date recent = new Date(System.currentTimeMillis() - 60 * 60 * 1000);
    for (int i = 0; i < RELEASED + HELD; i++) {
      Document taskRun =
          new Document("type", "template")
              .append("status", "cancelled")
              .append("phase", "completed")
              .append("workflowRunRef", "claim-index-plan-run")
              .append("name", "task-" + i)
              .append("creationDate", recent);
      if (i < HELD) {
        taskRun.append("claim", new Document("by", "claim-index-plan-dispatcher"));
      }
      history.add(taskRun);
    }
    collection().insertMany(history);

    Document terminal = explain(TaskRunService.terminationQueries(TEMPLATES, 20).get(0));
    print("termination check", terminal);

    assertThat(indexOf(terminal)).isEqualTo("claim_page");
    assertThat(stats(terminal).getInteger("totalDocsExamined"))
        .as("only held task runs are read, not the %d released ones", RELEASED)
        .isLessThan(RELEASED / 10);
    assertThat(stats(terminal).getInteger("nReturned")).isGreaterThanOrEqualTo(HELD);
  }

  @Test
  void theClaimPageKeepsAnIndexOrderedPlan() {
    List<Document> ready = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ready.add(
          new Document("type", "template")
              .append("status", "ready")
              .append("phase", "pending")
              .append("workflowRunRef", "claim-index-plan-run")
              .append("name", "ready-" + i)
              .append("creationDate", new Date(System.currentTimeMillis() - i * 1000L)));
    }
    collection().insertMany(ready);

    Document claimable = explain(TaskRunService.claimableQuery(TEMPLATES, 20));
    print("claim page", claimable);

    assertThat(indexOf(claimable)).isEqualTo("claim_page");
    assertThat(stats(claimable).getInteger("nReturned")).isGreaterThanOrEqualTo(5);
    assertThat(
            stages(claimable.get("queryPlanner", Document.class).get("winningPlan", Document.class)))
        .as("the page is read in index order, with no in-memory sort")
        .doesNotContain("SORT");
  }

  private Document explain(Query query) {
    QueryMapper mapper = new QueryMapper(mongoTemplate.getConverter());
    MongoPersistentEntity<?> entity =
        mongoTemplate.getConverter().getMappingContext().getPersistentEntity(TaskRunEntity.class);
    return collection()
        .find(mapper.getMappedObject(query.getQueryObject(), entity))
        .projection(query.getFieldsObject())
        .sort(query.getSortObject())
        .limit(query.getLimit())
        .explain(ExplainVerbosity.EXECUTION_STATS);
  }

  private static void print(String query, Document explained) {
    System.out.printf(
        "QUERY PLAN | %-17s | index %s | %d keys, %d documents read | %d returned%n",
        query,
        indexOf(explained),
        stats(explained).getInteger("totalKeysExamined"),
        stats(explained).getInteger("totalDocsExamined"),
        stats(explained).getInteger("nReturned"));
  }

  private MongoCollection<Document> collection() {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class));
  }

  private static Document stats(Document explained) {
    return explained.get("executionStats", Document.class);
  }

  // The index the winning plan scans.
  private static String indexOf(Document explained) {
    return indexName(explained.get("queryPlanner", Document.class).get("winningPlan", Document.class));
  }

  private static String indexName(Document stage) {
    if (stage == null) {
      return null;
    }
    if (stage.getString("indexName") != null) {
      return stage.getString("indexName");
    }
    String found = indexName(stage.get("inputStage", Document.class));
    if (found == null && stage.get("inputStages") instanceof List<?> inputs) {
      for (Object input : inputs) {
        found = (found != null) ? found : indexName((Document) input);
      }
    }
    return found;
  }

  private static List<String> stages(Document stage) {
    List<String> names = new ArrayList<>();
    if (stage == null) {
      return names;
    }
    names.add(stage.getString("stage"));
    names.addAll(stages(stage.get("inputStage", Document.class)));
    if (stage.get("inputStages") instanceof List<?> inputs) {
      inputs.forEach(input -> names.addAll(stages((Document) input)));
    }
    return names;
  }
}
