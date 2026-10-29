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
 * Integrations a workspace links through a form. Every integration template gains its {@code kind}
 * from {@code seed/integration-templates.json}: GitHub and Slack are {@code app}, installed by Flow,
 * where a stored template has none. The {@code form} templates of that file, Google and Microsoft,
 * whose fields the workspace enters itself, are inserted where the install holds no template of their
 * type.
 *
 * <p>An empty database receives every template from {@link _0020__SeedTemplates}, which seeds the
 * whole file, and this unit then finds nothing to change.
 */
@Change(id = "0034-seed-form-integrations", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0034__SeedFormIntegrations {

  private static final Logger LOG = LoggerFactory.getLogger(_0034__SeedFormIntegrations.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> templates = db.getCollection(names.resolve("integration_templates"));
    List<Document> seed = SeedResources.load("seed/integration-templates.json");
    for (Document template : seed) {
      String type = template.getString("type");
      String kind = template.getString("kind");
      if ("form".equals(kind)) {
        if (SeedResources.insertIfAbsent(
            db, names.resolve("integration_templates"), Filters.eq("type", type), template)) {
          LOG.info("Inserted the {} integration template", template.getString("name"));
        }
      } else {
        long updated =
            templates
                .updateMany(
                    Filters.and(Filters.eq("type", type), Filters.exists("kind", false)),
                    Updates.set("kind", kind))
                .getModifiedCount();
        LOG.info("Set kind {} on {} {} integration template(s)", kind, updated, type);
      }
    }
  }

  @Rollback
  public void rollback() {
    // Workspaces may already hold a form integration's parameters - forward-only, like every
    // other seed unit in this chain.
  }
}
