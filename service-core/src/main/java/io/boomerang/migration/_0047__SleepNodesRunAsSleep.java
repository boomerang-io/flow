package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Types {@code sleep} every workflow node that runs the native {@code sleep} task but is typed
 * {@code template}. The v3 upgrade turns v3's templated Sleep task into the native one ({@link
 * _0006__V3MigrateTaskCatalogue}) but keeps the nodes using it typed {@code template} ({@link
 * _0010__V3MigrateWorkflows}), so they run as containers with no command and fail. A node's type
 * is what the engine runs, so it must be its task's. A node already typed {@code sleep}, or typed
 * anything other than {@code template}, is left as it is; a second run finds nothing.
 */
@Change(id = "0047-sleep-nodes-run-as-sleep", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0047__SleepNodesRunAsSleep {

  private static final Logger LOG = LoggerFactory.getLogger(_0047__SleepNodesRunAsSleep.class);

  static final String FROM = "template";
  static final String TO = "sleep";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<String> sleepTaskIds = new ArrayList<>();
    for (Document task :
        db.getCollection(names.resolve("tasks"))
            .find(Filters.eq("type", TO))
            .projection(Projections.include("_id"))) {
      sleepTaskIds.add(task.get("_id").toString());
    }
    if (sleepTaskIds.isEmpty()) {
      LOG.info("No sleep task in the catalogue: no workflow node to retype");
      return;
    }

    MongoCollection<Document> revisions = db.getCollection(names.resolve("workflow_revisions"));
    Bson mistyped =
        Filters.elemMatch("tasks", Filters.and(Filters.in("taskRef", sleepTaskIds), Filters.eq("type", FROM)));
    int nodes = 0;
    int revised = 0;
    for (Document revision : revisions.find(mistyped)) {
      List<Document> tasks = revision.getList("tasks", Document.class);
      int retyped = retype(tasks, sleepTaskIds);
      revisions.updateOne(Filters.eq("_id", revision.get("_id")), Updates.set("tasks", tasks));
      nodes += retyped;
      revised++;
    }
    LOG.info("Typed {} sleep node(s) as {} in {} workflow revision(s)", nodes, TO, revised);
  }

  // Type sleep each node typed template that references a sleep task; how many were retyped.
  static int retype(List<Document> tasks, List<String> sleepTaskIds) {
    int retyped = 0;
    for (Document task : tasks) {
      if (FROM.equals(task.getString("type")) && sleepTaskIds.contains(task.getString("taskRef"))) {
        task.put("type", TO);
        retyped++;
      }
    }
    return retyped;
  }

  @Rollback
  public void rollback() {
    // Forward-only: a template-typed sleep node does not run.
  }
}
