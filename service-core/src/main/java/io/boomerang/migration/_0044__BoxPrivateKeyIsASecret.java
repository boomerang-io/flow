package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.boomerang.core.SecretCipher;
import io.boomerang.core.model.AESAlgorithm;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The Box tasks' {@code privateKey} is a credential: its parameter becomes a {@code password} on
 * the task revisions that typed it as text, and the value a workflow already holds for it becomes a
 * {@code secret} run param, encrypted at rest under the platform cipher like every other secret.
 * A value already encrypted, blank or the redaction marker is left as it is.
 */
@Change(id = "0044-box-private-key-is-a-secret", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0044__BoxPrivateKeyIsASecret {

  private static final Logger LOG = LoggerFactory.getLogger(_0044__BoxPrivateKeyIsASecret.class);
  static final String PARAM = "privateKey";
  private static final String TEXT = "texteditor::text";
  private static final String REDACTED = "*****";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    List<String> taskIds = new ArrayList<>();
    for (Document revision :
        revisions.find(
            Filters.elemMatch(
                "spec.params", Filters.and(Filters.eq("name", PARAM), Filters.eq("type", TEXT))))) {
      taskIds.add(revision.getString("parentRef"));
    }
    long retyped =
        revisions
            .updateMany(
                Filters.elemMatch(
                    "spec.params", Filters.and(Filters.eq("name", PARAM), Filters.eq("type", TEXT))),
                Updates.set("spec.params.$[p].type", "password"),
                new UpdateOptions()
                    .arrayFilters(List.of(Filters.and(Filters.eq("p.name", PARAM), Filters.eq("p.type", TEXT)))))
            .getModifiedCount();
    LOG.info("Typed {} as a password on {} Box task revision(s)", PARAM, retyped);
    if (taskIds.isEmpty()) {
      return;
    }

    String secret = env.getProperty(_0022__ReencryptSettingsAesGcm.SECRET, "");
    String salt = env.getProperty(_0022__ReencryptSettingsAesGcm.SALT, "");
    MongoCollection<Document> workflowRevisions =
        db.getCollection(names.resolve("workflow_revisions"));
    int sealed = 0;
    for (Document revision :
        workflowRevisions.find(Filters.elemMatch("tasks", Filters.in("taskRef", taskIds)))) {
      List<Document> tasks = revision.getList("tasks", Document.class);
      if (!seal(tasks, taskIds, secret, salt)) {
        continue;
      }
      workflowRevisions.updateOne(
          Filters.eq("_id", revision.get("_id")), Updates.set("tasks", tasks));
      sealed++;
    }
    LOG.info("Sealed the Box {} in {} workflow revision(s)", PARAM, sealed);
  }

  // Type the Box tasks' privateKey param as a secret and encrypt a plain value; whether anything
  // changed.
  static boolean seal(List<Document> tasks, List<String> taskIds, String secret, String salt) {
    boolean changed = false;
    for (Document task : tasks) {
      if (!taskIds.contains(task.getString("taskRef")) || task.getList("params", Document.class) == null) {
        continue;
      }
      for (Document param : task.getList("params", Document.class)) {
        if (!PARAM.equals(param.getString("name"))) {
          continue;
        }
        if (!"secret".equals(param.getString("type"))) {
          param.put("type", "secret");
          changed = true;
        }
        if (param.get("value") instanceof String value
            && !value.isBlank()
            && !REDACTED.equals(value)
            && !SecretCipher.isEncrypted(value)) {
          if (secret.isBlank() || salt.isBlank()) {
            throw new IllegalStateException(
                _0022__ReencryptSettingsAesGcm.SECRET
                    + " and "
                    + _0022__ReencryptSettingsAesGcm.SALT
                    + " must be set: a Box private key needs encrypting");
          }
          param.put(
              "value",
              _0022__ReencryptSettingsAesGcm.NEW_PREFIX
                  + AESAlgorithm.encrypt(value, secret, salt)
                  + "}");
          changed = true;
        }
      }
    }
    return changed;
  }

  @Rollback
  public void rollback() {
    // Forward-only: a credential is not written back in the clear.
  }
}
