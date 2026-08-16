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
 * V3-only. Single pass: v3 {@code users} (57 documents on the verified real dump) -> v5 {@code
 * UserEntity}, plus one personal {@code WorkspaceEntity} per user (ruling M-1: KEEP).
 *
 * <p>This squashes the user-reshaping half of legacy changeset {@code 4014} ({@code
 * v4MigrateUsersToTeam}) - the other half (creating a {@code MEMBEROF} relationship document and
 * re-pointing every prior workflow/run -\> user relationship at the new personal team) is
 * relationship-graph work and belongs to Batch E ({@code _0012__V3BuildRelationshipGraph}), which
 * this unit deliberately never touches (see the "user -\> personal-workspace linkage" section
 * below for how it stays discoverable there).
 *
 * <p><b>User field mapping, verified against a real v3 dump and against {@code UserEntity}/{@code
 * UserSettings}:</b>
 *
 * <ul>
 *   <li>{@code _id} preserved verbatim.
 *   <li>{@code email}, {@code name} <- passed straight through (v3 {@code name} is sometimes a
 *       display name, "Tyson", sometimes literally the email, "admin@flowabl.io" - both are real in
 *       the dump; carried as-is either way, matching {@code UserService.getAndRegisterUser}'s own
 *       fallback of using the email as the name when none is supplied). {@code displayName} is left
 *       unset - v3 never had a distinct display name and the live app itself never sets it at
 *       creation time either (only {@code name}), so inventing one here would be dishonest.
 *   <li>{@code type} <- v3 {@code type} (only {@code user}/{@code admin} appear in the real dump,
 *       both exact {@link io.boomerang.core.enums.UserType} members) - passed straight through;
 *       falls back to {@code user} (the entity's own default) for anything unrecognised.
 *   <li>{@code creationDate} <- v3 {@code firstLoginDate}; falls back to {@code lastLoginDate} then
 *       {@code new Date()} for the 4 real users missing {@code firstLoginDate} entirely (never left
 *       unset - unlike an entity read via Spring Data, this unit writes a raw {@link Document}, so
 *       an absent key would NOT pick up {@code UserEntity}'s {@code = new Date()} field default;
 *       it would just be absent/null forever).
 *   <li>{@code lastLoginDate} <- v3 {@code lastLoginDate}, when present.
 *   <li>{@code status} <- v3 {@code status} (values on the real dump: {@code active} only) - passed
 *       straight through; matches {@link io.boomerang.core.enums.UserStatus} directly.
 *   <li>{@code labels} <- v3 {@code labels[]} ({@code {key,value}} documents) -\> {@code
 *       Map<String,String>}, matching every other squashed unit's label-array convention.
 *   <li>{@code settings.isFirstVisit} <- v3 {@code isFirstVisit} (default {@code true}, the v3 and
 *       v5 default agree). {@code settings.hasConsented} <- v3 {@code hasConsented} (default {@code
 *       false} - only 20 of 57 real users carry the field at all). {@code settings.isShowHelp} has
 *       no v3 source at all (a v5-only field) - left at {@link
 *       io.boomerang.core.model.UserSettings}'s own default ({@code true}).
 *   <li>{@code quotas} - dropped per the batch instructions (quotas move to the personal workspace
 *       below with settings-derived defaults, never the per-user override v3 carried).
 *   <li><b>{@code flowTeams}</b> - NOT dropped outright, despite the batch instructions' "membership
 *       in other v3 teams is relationship-graph data for Batch E" framing. Verified against the
 *       real dump: {@code users.flowTeams: List<String>} (v3 {@code FlowUserEntity.flowTeams}) is
 *       the ONLY source of v3 team membership - v3 {@code TeamEntity} carries no embedded {@code
 *       users[]} counterpart (confirmed against the v3 entity shape). If this unit rewrote the user
 *       document without preserving it, {@code flowTeams} would be gone from {@code users} by the
 *       time Batch E runs (this unit replaces the whole document), the exact same class of
 *       ownership-loss bug flagged for {@code workflows.flowTeamId}/{@code ownerUserId} in {@code
 *       _0023} - except here there is no "check whether it still exists elsewhere" escape hatch,
 *       because nothing else in the database carries it. So it is preserved under {@code
 *       flowTeamRefs} - an extra field undeclared by {@code UserEntity} (same DD-08-compliant
 *       discoverability technique as {@code _0023}'s {@code scope}/{@code ownerRef} and {@code
 *       _0027}'s {@code workspaceRef}): the real v3 team ids the user belonged to, passed through
 *       verbatim (empty list when absent/empty - 2 of the 3 real users spot-checked in this program
 *       carry an empty {@code flowTeams}). Batch E ({@code _0012__V3BuildRelationshipGraph}) reads
 *       this to emit {@code user:<id> --memberOf--> workspace:<teamId>} edges for real (non-personal)
 *       team membership, skipping any id that does not resolve to a migrated workspace.
 * </ul>
 *
 * <p><b>Personal workspace per user (M-1).</b> Reproduces legacy {@code 4014}'s naming derivation
 * literally (a deliberate departure from {@code _0007__V3MigrateWorkspaces}'s consistency-driven
 * choice to use {@code _0022}'s simpler slug algorithm for ordinary teams - this one is instructed
 * to match {@code 4014} exactly, character-stripping regex included):
 *
 * <pre>
 *   displayName = userName.replace("@", "-").replace(".", "-") + " Personal Team"
 *   name        = displayName, then: strip everything except [A-Za-z0-9' -], collapse whitespace
 *                 to "-", turn "'" into "-", collapse repeated "-", lowercase
 * </pre>
 *
 * <p>{@code type} is {@link io.boomerang.workspace.model.WorkspaceType#personal}. {@code status}
 * mirrors the user's own ({@code active} user -\> {@code active} workspace, matching {@code
 * 4014}). {@code quotas} are the DEFAULT quotas the migrated {@code teams} settings document
 * carries (10 workflows / 20 runs-per-month / 25 storage / 2 run-storage / 30 min duration / 4
 * concurrent - see {@code _0005__V3MigrateSettings}'s {@code migrateTeams}), never a per-user
 * override even where the v3 document has its own {@code quotas} - the batch instructions are
 * explicit ("default quotas from the teams setting"), and those numbers are exactly {@code 4014}'s
 * own hardcoded fallback besides.
 *
 * <p><b>The user -\> personal-workspace linkage, made discoverable for Batch E without writing
 * {@code rel_edges} here (out of scope for this unit):</b> the personal workspace's {@code
 * externalRef} is set to the owning user's original v3 {@code _id}, as a string. Batch E can
 * therefore find every personal workspace and its owner with a single query - {@code teams} where
 * {@code type = "personal"}, reading {@code externalRef} as the user id - and write the {@code
 * user:<id> --memberOf--> workspace:<id>} edge {@code 4014} used to write directly, without needing
 * to re-derive anything from the (by then long since rewritten) {@code users} documents. {@code
 * externalRef} was chosen over an annotation (the technique {@code _0027} uses for its own
 * migration-provenance bookkeeping) deliberately: this is data a later unit reads to decide what
 * graph edge to create, and CLAUDE.md's DD-08 is explicit that anything read to decide must be a
 * typed field, never a {@code boomerang.io/*} annotation.
 *
 * <p><b>Idempotency</b> (the batch instructions flag this as especially important here): per-user
 * processing is gated on the v3 {@code _class} discriminator, matching {@code
 * _0007__V3MigrateWorkspaces} - a document rewritten by a prior run never carries it again. Within
 * one user's processing, the personal workspace is created FIRST, via {@link
 * SeedResources#insertIfAbsent} keyed on {@code (type=personal, externalRef=<userId>)}, and the
 * user document is rewritten (losing {@code _class}) SECOND - so a crash between the two steps
 * leaves the user still {@code _class}-tagged and the next run retries both, with the
 * insert-if-absent guard making the workspace half a safe no-op if it already exists. Reversing
 * that order would let a crash after the user rewrite permanently skip personal-workspace creation
 * for that user, since the outer {@code _class} gate would never see them again.
 */
@Change(id = "0008-v3-migrate-users", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0008__V3MigrateUsers {

  private static final Logger LOG = LoggerFactory.getLogger(_0008__V3MigrateUsers.class);

  /** See the class javadoc's "Personal workspace per user" section. */
  private static final int DEFAULT_MAX_WORKFLOW_COUNT = 10;

  private static final int DEFAULT_MAX_WORKFLOW_RUN_MONTHLY = 20;
  private static final int DEFAULT_MAX_WORKFLOW_STORAGE = 25;
  private static final int DEFAULT_MAX_WORKFLOW_RUN_STORAGE = 2;
  private static final int DEFAULT_MAX_WORKFLOW_RUN_DURATION = 30;
  private static final int DEFAULT_MAX_CONCURRENT_RUNS = 4;

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — users already migrated (or never existed) in v5 shape.");
      return;
    }

    MongoCollection<Document> users = db.getCollection(names.resolve("users"));
    String teamsCollection = names.resolve("teams");

    long migrated = 0;
    long personalWorkspacesCreated = 0;
    // "_class" is the v3 discriminator every real user document carries.
    for (Document source : users.find(Filters.exists("_class")).into(new ArrayList<>())) {
      ObjectId userId = source.getObjectId("_id");
      if (createPersonalWorkspaceIfAbsent(db, teamsCollection, source, userId)) {
        personalWorkspacesCreated++;
      }
      users.replaceOne(Filters.eq("_id", userId), migrateUser(source, userId));
      migrated++;
    }

    LOG.info(
        "v3 users migrated to v5 shape — {} migrated, {} personal workspace(s) created",
        migrated,
        personalWorkspacesCreated);
  }

  private Document migrateUser(Document source, ObjectId userId) {
    Document user = new Document();
    user.put("_id", userId);
    user.put("email", source.getString("email"));
    user.put("name", source.getString("name"));
    String type = source.getString("type");
    user.put("type", type != null ? type : "user");
    user.put("creationDate", resolveCreationDate(source));
    Date lastLoginDate = source.getDate("lastLoginDate");
    if (lastLoginDate != null) {
      user.put("lastLoginDate", lastLoginDate);
    }
    String status = source.getString("status");
    user.put("status", status != null ? status : "active");
    user.put("labels", convertLabels(source.get("labels")));
    user.put(
        "settings",
        new Document("isFirstVisit", source.getBoolean("isFirstVisit", Boolean.TRUE))
            .append("isShowHelp", Boolean.TRUE)
            .append("hasConsented", source.getBoolean("hasConsented", Boolean.FALSE)));
    // See the class javadoc's "flowTeams" bullet - preserved for Batch E, not a UserEntity field.
    user.put("flowTeamRefs", stringList(source.get("flowTeams")));
    return user;
  }

  @SuppressWarnings("unchecked")
  private List<String> stringList(Object raw) {
    List<String> result = new LinkedList<>();
    if (raw instanceof List<?> list) {
      for (Object entry : list) {
        if (entry != null) {
          result.add(entry.toString());
        }
      }
    }
    return result;
  }

  private Date resolveCreationDate(Document source) {
    Date firstLoginDate = source.getDate("firstLoginDate");
    if (firstLoginDate != null) {
      return firstLoginDate;
    }
    Date lastLoginDate = source.getDate("lastLoginDate");
    return lastLoginDate != null ? lastLoginDate : new Date();
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

  /**
   * See the class javadoc's "Personal workspace per user" and "Idempotency" sections.
   *
   * @return true if a new personal workspace was inserted
   */
  private boolean createPersonalWorkspaceIfAbsent(
      MongoDatabase db, String teamsCollection, Document source, ObjectId userId) {
    String userIdString = userId.toString();
    String userName = source.getString("name");
    if (userName == null || userName.isBlank()) {
      userName = source.getString("email");
    }
    String displayName = userName.replace("@", "-").replace(".", "-") + " Personal Team";

    Document workspace = new Document();
    workspace.put("_id", new ObjectId());
    workspace.put("displayName", displayName);
    workspace.put("name", slugifyLegacy(displayName));
    workspace.put("creationDate", new Date());
    workspace.put("type", "personal");
    workspace.put("status", "active".equals(source.getString("status")) ? "active" : "inactive");
    workspace.put("externalRef", userIdString);
    workspace.put("labels", new Document());
    workspace.put(
        "annotations",
        new Document("boomerang#io/generation", "3").append("boomerang#io/kind", "PersonalWorkspace"));
    workspace.put("parameters", new LinkedList<>());
    workspace.put(
        "quotas",
        new Document("maxWorkflowCount", DEFAULT_MAX_WORKFLOW_COUNT)
            .append("maxWorkflowRunMonthly", DEFAULT_MAX_WORKFLOW_RUN_MONTHLY)
            .append("maxWorkflowStorage", DEFAULT_MAX_WORKFLOW_STORAGE)
            .append("maxWorkflowRunStorage", DEFAULT_MAX_WORKFLOW_RUN_STORAGE)
            .append("maxWorkflowRunDuration", DEFAULT_MAX_WORKFLOW_RUN_DURATION)
            .append("maxConcurrentRuns", DEFAULT_MAX_CONCURRENT_RUNS));

    return SeedResources.insertIfAbsent(
        db,
        teamsCollection,
        Filters.and(Filters.eq("type", "personal"), Filters.eq("externalRef", userIdString)),
        workspace);
  }

  /** {@code 4011}'s slugification (reused verbatim by {@code 4014}): strip, collapse, lowercase. */
  private String slugifyLegacy(String value) {
    String slug = value.replaceAll("[^A-Za-z0-9' \\-]", "");
    slug = slug.replaceAll("\\s+", "-");
    slug = slug.replaceAll("'", "-");
    slug = slug.replaceAll("-+", "-");
    return slug.toLowerCase();
  }

  @Rollback
  public void rollback() {
    // User documents are rewritten in place with no v3 field preserved anywhere else, and
    // personal workspaces are real workspace documents by the time this could run - not
    // restorable, matching the other forward-only v3-only units in this chain.
  }
}
