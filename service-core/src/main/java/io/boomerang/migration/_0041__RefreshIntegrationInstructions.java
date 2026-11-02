package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Replaces each integration template's {@code instructions} with the seed's, so an install seeded
 * before the text was rewritten shows the same Connect and Disconnect guidance as an empty database.
 * Only that field changes, and only on the templates the seed carries.
 */
@Change(id = "0041-refresh-integration-instructions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0041__RefreshIntegrationInstructions {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0041__RefreshIntegrationInstructions.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> templates = db.getCollection(names.resolve("integration_templates"));
    List<Document> seed = SeedResources.load("seed/integration-templates.json");
    for (Document template : seed) {
      String type = template.getString("type");
      long updated =
          templates
              .updateMany(
                  Filters.eq("type", type),
                  Updates.set("instructions", template.getString("instructions")))
              .getModifiedCount();
      LOG.info("Refreshed the instructions of {} {} integration template(s)", updated, type);
    }
  }

  @Rollback
  public void rollback() {
    // Forward-only: the earlier text is not kept.
  }
}
