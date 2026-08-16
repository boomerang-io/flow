package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Single pass: v3 {@code teams} (28 documents on the verified real dump) -> v5 {@code
 * WorkspaceEntity}, written directly in place in the same {@code teams} collection (the entity
 * kept the pre-DD-01 collection name — see {@code
 * io.boomerang.workspace.entity.WorkspaceEntity}'s {@code @Document}), plus {@code
 * approver_groups} for any embedded approver groups.
 *
 * <p>This SQUASHES legacy changesets {@code 4011} (the core team -> v4-shape transform) and {@code
 * 4044} (properties -> parameters key/values rename, folded into {@link #migrateParameter} rather
 * than left as a two-pass intermediate).
 *
 * <p><b>Field mapping, verified against a real v3 dump and against {@code
 * service-loader/src/main/resources/seed/workspace.json} (the seeded {@code system} workspace,
 * the known-correct v5 shape):</b>
 *
 * <ul>
 *   <li>{@code _id} preserved verbatim — every relationship-graph node a later batch writes ({@code
 *       workspace:<id>}) and every workflow/run reference depends on this.
 *   <li>{@code displayName} <- v3 {@code name} (the display name); {@code name} <- that same value
 *       slugified with {@code _0022__V3MigrateTasks}'s exact algorithm ({@code
 *       trim().toLowerCase().replace(' ', '-')}) rather than legacy {@code 4011}'s fancier
 *       character-stripping regex — a deliberate maintainer-directed departure from legacy fidelity
 *       for consistency across this codebase's v3-\>v5 units. Two name pairs collide on this
 *       algorithm in the real dump ("Team Glen" x2, " Team" x2) — there is no unique index on
 *       {@code teams.name} at the Mongo level or in {@code WorkspaceEntity}, so this is a
 *       pre-existing v3 data-quality issue carried through, not a migration bug.
 *   <li>{@code creationDate} <- {@code new Date()} at migration time. v3 {@code teams} carries no
 *       creation-date field at all (confirmed in the dump) — {@code 4011} stamped the same thing.
 *   <li>{@code type} <- {@link io.boomerang.workspace.model.WorkspaceType#hobby}. v3 teams have no
 *       tier concept whatsoever and nothing in the current service code assigns a default type on
 *       create either; {@code hobby} is chosen as the honest "no tier information" value, distinct
 *       from {@code personal} (reserved for {@link _0028__V3MigrateUsers}'s per-user workspaces) and
 *       {@code system} (reserved for the seeded {@code system} workspace this unit never touches).
 *   <li>{@code status} <- v3 {@code isActive}: {@code true} -\> {@code active}, {@code false} -\>
 *       {@code inactive} (matches {@link io.boomerang.workspace.model.WorkspaceStatus}).
 *   <li>{@code externalRef} <- v3 {@code higherLevelGroupId}, when present (20 of 28 real teams
 *       have none - left unset, matching the entity's nullable field).
 *   <li>{@code labels} <- v3 {@code labels[]} ({@code {key,value}} documents, matching every other
 *       squashed unit's label-array convention) -\> {@code Map<String,String>}; defaults to {@code
 *       {}} (no team in the real dump carries this field at all).
 *   <li>{@code annotations} <- {@code {"boomerang#io/generation":"3"}} (the {@code #}-for-{@code .}
 *       escaping {@code MongoConfiguration.setMapKeyDotReplacement("#")} applies, matching {@code
 *       _0022}'s task annotations) - migration-provenance bookkeeping only, v3 has no equivalent.
 *   <li>{@code parameters} <- v3 {@code settings.properties[]} (legacy {@code 4011}'s bump-up),
 *       transformed per {@code 4044}: {@code key}-\>{@code name}, {@code values}-\>{@code value} if
 *       present (defensive - the real dump's one populated property already carries singular
 *       {@code value}, so this branch does not fire on real data), the v3 property's own random
 *       {@code _id} dropped (not a field {@link io.boomerang.common.model.AbstractParam} has).
 *       Defaults to {@code []}.
 *   <li>{@code quotas} <- v3 {@code quotas} (all real values are plain integers, not the
 *       unit-suffixed strings the {@code settings} collection's storage entries carry - verified in
 *       the dump): {@code maxWorkflowCount}-\>{@code maxWorkflowCount}, {@code
 *       maxWorkflowExecutionMonthly}-\>{@code maxWorkflowRunMonthly}, {@code maxWorkflowStorage}-\>
 *       {@code maxWorkflowStorage}, {@code maxWorkflowExecutionTime}-\>{@code
 *       maxWorkflowRunDuration}, {@code maxConcurrentWorkflows}-\>{@code maxConcurrentRuns}. {@code
 *       maxWorkflowRunStorage} has no v3 source at all (a genuinely new v5 field) - defaulted to
 *       {@link #DEFAULT_MAX_WORKFLOW_RUN_STORAGE} (2), the numeric value the migrated {@code teams}
 *       settings document's {@code max.workflowrun.storage} entry carries ({@code "2Gi"} - see
 *       {@code _0021__V3MigrateSettings}); the system workspace's {@code Integer.MAX_VALUE} would
 *       be dishonest for a regular quota-bound team.
 * </ul>
 *
 * <p><b>Approver groups</b> (ruling M-1's sibling concern - legacy {@code 4011} stripped {@code
 * teams.approverGroups[]} and never wrote a replacement collection, so every v4 install lost this
 * data outright; this unit must not repeat that). Extracted into {@code approver_groups} ({@link
 * io.boomerang.workspace.entity.ApproverGroupEntity}: {@code name}/{@code creationDate}/{@code
 * approvers}) with a fresh {@code _id} (matching {@code 4011}'s own {@code new ObjectId()}), plus
 * an extra {@code workspaceRef} field the entity does not declare (harmless - inserted via the raw
 * driver, ignored by {@code MappingMongoConverter} until read, never surfaced by {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)}) so a later batch can find which workspace each
 * approver group belongs to without re-deriving it from the now-stripped {@code teams} document -
 * the same discoverability need {@link _0028__V3MigrateUsers} solves with {@code externalRef} for
 * personal workspaces, but {@code ApproverGroupEntity} has no such field to repurpose. In the real
 * dump both teams carrying {@code approverGroups} (SRC Innovations, Uvis Team) have EMPTY arrays -
 * the populated shape is UNVALIDATED. Handled defensively: v3's {@code approvers[]} is assumed to
 * be either a list of {@code {userId, ...}} documents (matching {@code 4011}'s own read of that
 * shape) or already a list of bare user-id strings; either resolves to {@code List<String>}.
 *
 * <p>Skip logic / idempotency: only {@code teams} documents still carrying the v3 {@code _class}
 * discriminator ({@code io.boomerang.mongo.entity.TeamEntity}) are processed - the seeded {@code
 * system} workspace ({@code _0014}, no {@code _class} at all) is naturally excluded without any
 * special-case id check, and a second run finds nothing left with {@code _class} to process
 * (documents are rewritten from scratch, never leaving it behind).
 */
@Change(id = "0027-v3-migrate-workspaces", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0027__V3MigrateWorkspaces {

  private static final Logger LOG = LoggerFactory.getLogger(_0027__V3MigrateWorkspaces.class);

  /** See the class javadoc's {@code quotas} bullet. */
  private static final int DEFAULT_MAX_WORKFLOW_RUN_STORAGE = 2;

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — teams already migrated (or never existed) in v5 shape.");
      return;
    }

    MongoCollection<Document> teams = db.getCollection(names.resolve("teams"));
    MongoCollection<Document> approverGroups = db.getCollection(names.resolve("approver_groups"));

    long migrated = 0;
    long approverGroupsExtracted = 0;
    // "_class" is the v3 discriminator every real team document carries; the seeded system
    // workspace (_0014) never has one, so it is never matched here.
    for (Document source : teams.find(Filters.exists("_class")).into(new ArrayList<>())) {
      ObjectId workspaceId = source.getObjectId("_id");
      approverGroupsExtracted +=
          extractApproverGroups(source, workspaceId, approverGroups);
      teams.replaceOne(Filters.eq("_id", workspaceId), migrateWorkspace(source, workspaceId));
      migrated++;
    }

    LOG.info(
        "v3 teams migrated to v5 workspaces — {} migrated, {} approver group(s) extracted",
        migrated,
        approverGroupsExtracted);
  }

  private Document migrateWorkspace(Document source, ObjectId workspaceId) {
    Document workspace = new Document();
    workspace.put("_id", workspaceId);
    String displayName = source.getString("name");
    workspace.put("displayName", displayName);
    workspace.put("name", slugify(displayName));
    workspace.put("creationDate", new Date());
    workspace.put("type", "hobby");
    workspace.put("status", Boolean.TRUE.equals(source.getBoolean("isActive")) ? "active" : "inactive");
    Object externalRef = source.get("higherLevelGroupId");
    if (externalRef != null) {
      workspace.put("externalRef", externalRef.toString());
    }
    workspace.put("labels", convertLabels(source.get("labels")));
    workspace.put("annotations", new Document("boomerang#io/generation", "3"));
    workspace.put("parameters", migrateParameters(source));
    workspace.put("quotas", migrateQuotas(source));
    return workspace;
  }

  /** {@code 4004}/{@code _0022}'s slugification: {@code trim().toLowerCase().replace(' ', '-')}. */
  private String slugify(String displayName) {
    return displayName.trim().toLowerCase().replace(' ', '-');
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> convertLabels(Object rawLabels) {
    Map<String, String> labels = new HashMap<>();
    if (rawLabels instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Document label) {
          labels.put(label.getString("key"), label.getString("value"));
        }
      }
    }
    return labels;
  }

  /** {@code 4011}'s properties -\> parameters bump-up, then {@code 4044}'s key/values rename. */
  @SuppressWarnings("unchecked")
  private List<Document> migrateParameters(Document source) {
    Document settings = (Document) source.get("settings");
    if (settings == null) {
      return new LinkedList<>();
    }
    List<Document> properties = (List<Document>) settings.get("properties");
    if (properties == null) {
      return new LinkedList<>();
    }
    List<Document> parameters = new LinkedList<>();
    for (Document property : properties) {
      parameters.add(migrateParameter(property));
    }
    return parameters;
  }

  private Document migrateParameter(Document property) {
    Document param = new Document(property);
    param.remove("_id");
    if (param.get("key") != null && !param.get("key").toString().isEmpty()) {
      param.put("name", param.get("key"));
    }
    param.remove("key");
    Object values = param.get("values");
    if (values != null && !values.toString().isEmpty()) {
      param.put("value", values);
      param.remove("values");
    }
    return param;
  }

  private Document migrateQuotas(Document source) {
    Document quotas = (Document) source.get("quotas");
    Document result = new Document();
    if (quotas != null) {
      result.put("maxWorkflowCount", quotas.getInteger("maxWorkflowCount"));
      result.put("maxWorkflowRunMonthly", quotas.getInteger("maxWorkflowExecutionMonthly"));
      result.put("maxWorkflowStorage", quotas.getInteger("maxWorkflowStorage"));
      result.put("maxWorkflowRunDuration", quotas.getInteger("maxWorkflowExecutionTime"));
      result.put("maxConcurrentRuns", quotas.getInteger("maxConcurrentWorkflows"));
    }
    result.put("maxWorkflowRunStorage", DEFAULT_MAX_WORKFLOW_RUN_STORAGE);
    return result;
  }

  /** See the class javadoc's "Approver groups" section. */
  @SuppressWarnings("unchecked")
  private long extractApproverGroups(
      Document source, ObjectId workspaceId, MongoCollection<Document> approverGroups) {
    List<Document> rawGroups = (List<Document>) source.get("approverGroups");
    if (rawGroups == null || rawGroups.isEmpty()) {
      return 0;
    }
    long extracted = 0;
    for (Document rawGroup : rawGroups) {
      Document approverGroup = new Document();
      approverGroup.put("_id", new ObjectId());
      approverGroup.put("name", rawGroup.getString("name"));
      approverGroup.put("creationDate", new Date());
      approverGroup.put("approvers", resolveApprovers(rawGroup.get("approvers")));
      // Not a declared ApproverGroupEntity field - see the class javadoc for why this is here.
      approverGroup.put("workspaceRef", workspaceId.toString());
      approverGroups.insertOne(approverGroup);
      extracted++;
    }
    return extracted;
  }

  /**
   * v3's {@code approvers[]} shape is unvalidated (empty on every real record) - defensively
   * accepts either {@code {userId, ...}} documents (matching legacy {@code 4011}) or bare user-id
   * strings.
   */
  @SuppressWarnings("unchecked")
  private List<String> resolveApprovers(Object rawApprovers) {
    List<String> approvers = new LinkedList<>();
    if (rawApprovers instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Document approverDoc && approverDoc.get("userId") != null) {
          approvers.add(approverDoc.get("userId").toString());
        } else if (entry != null) {
          approvers.add(entry.toString());
        }
      }
    }
    return approvers;
  }

  @Rollback
  public void rollback() {
    // Team documents are rewritten in place with no v3 field preserved anywhere else - not
    // restorable, matching the other forward-only v3-only units in this chain.
  }
}
