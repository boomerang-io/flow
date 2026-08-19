package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * Single-field indexes on {@code workspaces.name} and {@code workspaces.displayName}. {@code
 * WorkspaceEntity} declares neither field {@code @Indexed} (and {@code
 * auto-index-creation=false} makes that annotation inert either way), so {@code
 * WorkspaceRepository.findByNameIgnoreCase}/{@code deleteByName} and {@code
 * WorkspaceService.findByCriteria}'s {@code name in (...)} clause were already an unindexed scan
 * before the {@code search} query param existed.
 *
 * <p><b>What this index does and does not fix.</b> {@code WorkspaceService.findByCriteria}'s
 * {@code search} clause is an anchored, case-insensitive regex (
 * {@code Criteria.where("name").regex("^" + term, "i")}) ORed across {@code name}/{@code
 * displayName}. MongoDB's prefix-regex optimisation - using the index to bound the scan to a
 * contiguous range instead of walking every entry - only applies to a case-SENSITIVE anchored
 * regex; the {@code "i"} option defeats it regardless of collation, because collation orders
 * string comparisons ($eq/$lt/$gt/sort) and is not consulted by $regex pattern matching at all.
 * So these indexes turn the equality lookups above (exact-name get/delete, the plain {@code name
 * in (...)} membership filter) into real index seeks, and let the query planner IXSCAN the search
 * clause instead of walking the collection (avoiding a full document fetch per candidate) - but
 * the case-insensitive search itself still evaluates the regex against every index entry, not a
 * bounded range. Genuinely indexed case-insensitive prefix search needs a normalised (lowercased)
 * copy of each field compared with a case-SENSITIVE prefix query; that is a data-model change
 * (a new stored field, backfilled and kept in sync on write) out of scope here - flagged for a
 * follow-up rather than added speculatively.
 */
@Change(id = "0030-workspace-search-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0030__WorkspaceSearchIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String workspaces = names.resolve("workspaces");
    ensureIndex(db, workspaces, "name_lookup", new Document("name", 1), new IndexOptions());
    ensureIndex(
        db,
        workspaces,
        "display_name_lookup",
        new Document("displayName", 1),
        new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String workspaces = names.resolve("workspaces");
    dropIndex(db, workspaces, "name_lookup");
    dropIndex(db, workspaces, "display_name_lookup");
  }
}
