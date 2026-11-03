package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Clears the placeholder a few seeded task revisions carried as the default of a credential
 * parameter (older GitHub revisions' {@code token}, the SendGrid template task's {@code apiKey} and
 * {@code templateId}): a default that is sent as a real key when nothing is entered. The parameter
 * stays; only that default goes.
 */
@Change(id = "0045-clear-placeholder-defaults", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0045__ClearPlaceholderDefaults {

  private static final Logger LOG = LoggerFactory.getLogger(_0045__ClearPlaceholderDefaults.class);
  static final String PLACEHOLDER = "youll-come-a-waltzing-maltilda-with-me";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    long cleared =
        db.getCollection(names.resolve("task_revisions"))
            .updateMany(
                Filters.eq("spec.params.defaultValue", PLACEHOLDER),
                Updates.set("spec.params.$[p].defaultValue", ""),
                new UpdateOptions().arrayFilters(List.of(Filters.eq("p.defaultValue", PLACEHOLDER))))
            .getModifiedCount();
    LOG.info("Cleared the placeholder default on {} task revision(s)", cleared);
  }

  @Rollback
  public void rollback() {
    // Forward-only: the placeholder is not a value anyone wants back.
  }
}
