package io.boomerang.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.migration.InstallGeneration;
import io.boomerang.loader.migration.LegacyGenerationMarker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the full loader against a REAL v3 production database dump, rather than the synthetic
 * fixture {@link LoaderMigrationTest} builds by hand. This is the harness the whole v3→v5
 * consolidation program depends on: every later slice adds its assertions here rather than
 * re-deriving its own restore.
 *
 * <p><b>The dump is never checked into this repository.</b> It is real production data (customer
 * workflow names, user emails) and this is a public Apache-2.0 repo. It is located on disk by
 * path only, via {@link #DUMP_PATH_PROPERTY} (or {@link #DUMP_PATH_ENV}), defaulting to the path
 * the dump lives at on the machine this test was authored on. Anywhere else — including CI, which
 * has no dump — {@link #locateDumpAndRestore()} skips the whole class via {@link
 * Assumptions#assumeTrue}, so CI stays green.
 *
 * <p><b>Restore approach.</b> {@code mongorestore} is used when present on {@code PATH} (it is on
 * the dev machine this was authored on: {@code mongorestore --version} → 100.6.1) — a single
 * process invocation against the Testcontainers Mongo, renaming the dump's original database
 * ({@link #SOURCE_DATABASE}, baked into every {@code .metadata.json}'s {@code ns} field) onto the
 * container's database via {@code --nsFrom}/{@code --nsTo}; collection names inside that database
 * already carry the {@code flowabl_} prefix, so they need no rewriting. When {@code mongorestore}
 * is absent, {@link #restoreViaBsondump} falls back to {@code bsondump <file>.bson}, which emits
 * one MongoDB canonical Extended JSON document per line (confirmed on this dump — {@code $oid},
 * nested {@code $date}/{@code $numberLong}, {@code $numberInt} all appear). {@code
 * org.bson.Document#parse} reads canonical Extended JSON natively via the driver's {@code
 * JsonReader} — no hand-rolled wrapper handling is needed; each line is parsed and the resulting
 * documents are inserted straight into a collection named after the source file (stripping the
 * {@code .bson} suffix, which already preserves the dump's real prefixed/unprefixed names,
 * including the one lone unprefixed {@code locks.bson}).
 */
class V3DumpMigrationTest {

  private static final Logger LOG = LoggerFactory.getLogger(V3DumpMigrationTest.class);

  private static final String DUMP_PATH_PROPERTY = "flow.migration.v3dump";
  private static final String DUMP_PATH_ENV = "FLOW_MIGRATION_V3DUMP";
  private static final String DEFAULT_DUMP_PATH =
      "/Users/tysonlawrie/Workspaces/cheerdev/ops/flowabl-live-dump-20231106/boomerang";

  /** The database name baked into the dump's own {@code .metadata.json} {@code ns} fields. */
  private static final String SOURCE_DATABASE = "boomerang";

  /** The collection prefix already baked into the dump's collection names (verified on-disk). */
  private static final String COLLECTION_PREFIX = "flowabl";

  private static final String TARGET_DATABASE = "v3migration";

  private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  private static Path dumpDir;
  private static MongoClient client;
  private static MongoDatabase db;
  private static Map<String, Long> restoredCounts;

  @BeforeAll
  static void locateDumpAndRestore() throws IOException, InterruptedException {
    String configured =
        System.getProperty(
            DUMP_PATH_PROPERTY, System.getenv().getOrDefault(DUMP_PATH_ENV, DEFAULT_DUMP_PATH));
    dumpDir = Path.of(configured);
    Assumptions.assumeTrue(
        Files.isDirectory(dumpDir) && hasBsonFiles(dumpDir),
        () ->
            "Skipping V3DumpMigrationTest — no v3 dump directory found at '"
                + dumpDir
                + "'. Point -D"
                + DUMP_PATH_PROPERTY
                + "=<path> (or env "
                + DUMP_PATH_ENV
                + ") at a mongodump export to run this test; CI has none, so it always skips.");

    MONGO.start();
    client = MongoClients.create(MONGO.getReplicaSetUrl(TARGET_DATABASE));
    db = client.getDatabase(TARGET_DATABASE);

    restoredCounts = restoreDump();
    long totalDocs = restoredCounts.values().stream().mapToLong(Long::longValue).sum();
    LOG.info("Restored {} collections, {} documents total: {}", restoredCounts.size(), totalDocs, restoredCounts);
  }

  @AfterAll
  static void closeClient() {
    if (client != null) {
      client.close();
    }
  }

  private static boolean hasBsonFiles(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return files.anyMatch(p -> p.toString().endsWith(".bson"));
    } catch (IOException e) {
      return false;
    }
  }

  // =====================================================================================
  // Restore
  // =====================================================================================

  private static Map<String, Long> restoreDump() throws IOException, InterruptedException {
    if (isMongorestoreAvailable()) {
      restoreViaMongorestore();
    } else {
      restoreViaBsondump();
    }
    Map<String, Long> counts = new TreeMap<>();
    for (String name : db.listCollectionNames()) {
      counts.put(name, db.getCollection(name).countDocuments());
    }
    return counts;
  }

  private static boolean isMongorestoreAvailable() {
    try {
      Process process = new ProcessBuilder("mongorestore", "--version").redirectErrorStream(true).start();
      boolean finished = process.waitFor(10, TimeUnit.SECONDS);
      return finished && process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static void restoreViaMongorestore() throws IOException, InterruptedException {
    List<String> command =
        List.of(
            "mongorestore",
            "--uri=" + MONGO.getReplicaSetUrl(TARGET_DATABASE),
            "--nsFrom=" + SOURCE_DATABASE + ".*",
            "--nsTo=" + TARGET_DATABASE + ".*",
            "--dir=" + dumpDir.toAbsolutePath());
    LOG.info("Restoring v3 dump via mongorestore: {}", command);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("mongorestore timed out:\n" + output);
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("mongorestore failed (exit=" + process.exitValue() + "):\n" + output);
    }
    LOG.info("mongorestore output:\n{}", output);
  }

  /**
   * Fallback used only when {@code mongorestore} is not on {@code PATH}: parse each collection's
   * {@code .bson} file with {@code bsondump} (one canonical-Extended-JSON document per line) and
   * insert the parsed documents directly. No index restore in this path (the migration under test
   * does not depend on the dump's own indexes; {@code service-loader}'s index change units build
   * what v5 needs from scratch).
   */
  private static void restoreViaBsondump() throws IOException, InterruptedException {
    LOG.warn("mongorestore not found on PATH — falling back to bsondump-based restore");
    List<Path> bsonFiles;
    try (Stream<Path> files = Files.list(dumpDir)) {
      bsonFiles = files.filter(p -> p.toString().endsWith(".bson")).sorted().toList();
    }
    for (Path bsonFile : bsonFiles) {
      restoreCollectionViaBsondump(bsonFile);
    }
  }

  private static void restoreCollectionViaBsondump(Path bsonFile) throws IOException, InterruptedException {
    String fileName = bsonFile.getFileName().toString();
    String collectionName = fileName.substring(0, fileName.length() - ".bson".length());

    Process process = new ProcessBuilder("bsondump", bsonFile.toAbsolutePath().toString()).start();
    List<Document> documents = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          // Document.parse reads MongoDB canonical Extended JSON natively - $oid, $date
          // (including the nested $numberLong form bsondump emits), $numberInt, $numberLong
          // etc. all resolve to their real BSON types without any manual wrapper handling.
          documents.add(Document.parse(line));
        }
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
    if (!finished || process.exitValue() != 0) {
      throw new IllegalStateException("bsondump failed for " + bsonFile + " (exit=" + process.exitValue() + ")");
    }
    if (!documents.isEmpty()) {
      db.getCollection(collectionName).insertMany(documents);
    }
    LOG.info("Restored {} — {} documents (bsondump fallback)", collectionName, documents.size());
  }

  // =====================================================================================
  // The test
  // =====================================================================================

  @Test
  void migratesRealV3DumpToV5() {
    // -----------------------------------------------------------------------------------
    // Harness sanity — proves the restore actually loaded real v3 data before the
    // migration pipeline (and this test's assertions) can mean anything.
    // -----------------------------------------------------------------------------------
    assertThat(restoredCounts.values().stream().mapToLong(Long::longValue).sum())
        .as("total restored documents across all collections")
        .isGreaterThan(30_000);
    assertThat(collection("sys_changelog_flow").countDocuments()).isGreaterThanOrEqualTo(100);
    assertThat(collection("sys_changelog_flow").find(Filters.eq("changeId", "112")).first())
        .as("v3 chain marker must be present")
        .isNotNull();
    assertThat(collection("sys_changelog_flow").find(Filters.eq("changeId", "4000")).first())
        .as("v4 chain marker must be absent - this dump is proven v3, never upgraded to v4")
        .isNull();
    long workflowsBefore = collection("workflows").countDocuments();
    long usersBefore = collection("users").countDocuments();
    assertThat(workflowsBefore).isGreaterThan(0);
    assertThat(usersBefore).isGreaterThan(0);

    // -----------------------------------------------------------------------------------
    // Run the full Flamingock migration.
    // -----------------------------------------------------------------------------------
    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl(TARGET_DATABASE), COLLECTION_PREFIX))
        .doesNotThrowAnyException();

    assertGenerationRecorded();
    assertDeadCollectionsDropped();
    assertLiveCollectionsPreserved(workflowsBefore, usersBefore);
    assertGenerationAwareSeedsSkipped();
    assertSettingsMigrated();
    assertGlobalParametersMigrated();
    assertTaskCatalogueMigrated();
    assertTaskRunRefsUnitIsNoOp();
    assertWorkspacesMigrated();
    assertUsersMigrated();
    assertWorkflowsMigrated(workflowsBefore);
    assertTemplatesExtracted();
    assertRunsMigrated();
    assertActionsMigrated();
    assertSchedulesMigrated();
    assertRelationshipGraphBuilt();
    assertSystemWorkspaceMembersAttached();
    assertAuditSeeded();

    // -----------------------------------------------------------------------------------
    // Idempotency — a second full run changes nothing. Compared at per-collection document
    // count granularity across the WHOLE database (cheap and exhaustive - it would catch an
    // errant insert/delete in ANY collection, not just the ones this slice's assertions
    // happen to name) plus a byte-identical check on the small marker document _0019 writes.
    // -----------------------------------------------------------------------------------
    Map<String, Long> countsAfterFirstRun = snapshotCounts();
    Document markerAfterFirstRun =
        collection(LegacyGenerationMarker.COLLECTION).find(Filters.eq("_id", "legacyGeneration")).first();
    assertThat(markerAfterFirstRun).isNotNull();

    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl(TARGET_DATABASE), COLLECTION_PREFIX))
        .doesNotThrowAnyException();

    assertThat(snapshotCounts()).isEqualTo(countsAfterFirstRun);
    assertThat(collection(LegacyGenerationMarker.COLLECTION).find(Filters.eq("_id", "legacyGeneration")).first())
        .isEqualTo(markerAfterFirstRun);
    assertGenerationRecorded();
    assertDeadCollectionsDropped();
    assertGenerationAwareSeedsSkipped();
    assertSettingsMigrated();
    assertGlobalParametersMigrated();
    assertTaskCatalogueMigrated();
    assertTaskRunRefsUnitIsNoOp();
    // Re-asserting the exact post-migration counts here (86 teams: 28 v3 + 1 system + 57
    // personal; 57 users) after this second full run is itself the personal-workspace
    // idempotency proof the batch instructions ask for - a second run that created even one
    // duplicate personal workspace would push the teams count past 86.
    assertWorkspacesMigrated();
    assertUsersMigrated();
    // Re-asserting the exact post-migration counts here (65 workflows: 67 v3 minus the 2
    // extracted into workflow_templates; 149 workflow_revisions: 151 v3 minus the 2 belonging to
    // those extracted templates; 18093 workflow_runs; 8 actions; 90 workflow_schedules) after this
    // second full run is itself the idempotency proof the batch instructions ask for - a second
    // run that duplicated or re-deleted anything would push these counts off their real-dump
    // values.
    assertWorkflowsMigrated(workflowsBefore);
    assertTemplatesExtracted();
    assertRunsMigrated();
    assertActionsMigrated();
    assertSchedulesMigrated();
    // Re-asserting the graph/audit counts here after this second full run is itself the
    // idempotency proof the batch instructions ask for - a second run that duplicated even one
    // node, edge, or audit record would push these counts (or the graph invariants) off their
    // real-dump values.
    assertRelationshipGraphBuilt();
    assertSystemWorkspaceMembersAttached();
    assertAuditSeeded();
  }

  // =====================================================================================
  // _0019__LegacyGenerationDetect invariants
  // =====================================================================================

  private void assertGenerationRecorded() {
    Document marker =
        collection(LegacyGenerationMarker.COLLECTION).find(Filters.eq("_id", "legacyGeneration")).first();
    assertThat(marker).isNotNull();
    assertThat(marker.getString("generation")).isEqualTo(InstallGeneration.V3.name());
    assertThat(marker.getDate("detectedAt")).isNotNull();
    assertThat(collection(LegacyGenerationMarker.COLLECTION).countDocuments())
        .as("exactly one marker document, never duplicated by a re-run")
        .isEqualTo(1);
  }

  // =====================================================================================
  // _0020__V3DropDeadCollections invariants
  // =====================================================================================

  private void assertDeadCollectionsDropped() {
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    List<String> expectedDropped =
        List.of(
            "workflows_activity_task",
            "jobs",
            "triggers",
            "calendars",
            "paused_trigger_groups",
            "locks",
            "schedulers",
            "tasks_locks",
            "tokens");
    for (String dropped : expectedDropped) {
      assertThat(names)
          .as("%s must have been dropped", dropped)
          .doesNotContain(prefixed(dropped));
    }
    // The one unprefixed "locks" collection in the dump (distributed-lock's, not Quartz's) is
    // out of scope for this v3-only unit and must survive untouched.
    assertThat(names).as("unprefixed distributed-lock 'locks' left alone").contains("locks");
  }

  private void assertLiveCollectionsPreserved(long workflowsBefore, long usersBefore) {
    // Collections a LATER v3->v5 unit still needs the real v3 data from - not this slice's
    // job to touch, so they must be exactly as restored. (settings and global_config ARE
    // migrated by this slice's own _0021/_0031, task_templates by _0022, and workflows/
    // workflows_activity by Batch D's _0023/_0024/_0025 - see
    // assertSettingsMigrated/assertGlobalParametersMigrated/assertTaskCatalogueMigrated/
    // assertWorkflowsMigrated/assertRunsMigrated below, not here.) workflowsBefore is still used
    // by assertWorkflowsMigrated/assertTemplatesExtracted below to prove the exact count delta
    // the template extraction produces.
    assertThat(collection("users").countDocuments()).isEqualTo(usersBefore);
    assertThat(collection("settings").countDocuments()).isGreaterThan(0);
    assertThat(collection("teams").countDocuments()).isGreaterThan(0);
    // Not legacy at all - the live v5 ExtensionEntity collection, still written under this name.
    assertThat(collection("extensions").countDocuments()).isGreaterThan(0);
    // Kept forever: the historical record InstallGeneration.detect reads, and Mongock's own lock.
    assertThat(collection("sys_changelog_flow").countDocuments()).isGreaterThan(0);
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).contains(prefixed("sys_lock_flow"));
  }

  // =====================================================================================
  // Generation-aware seed skip invariants (_0016/_0017/_0018 - pre-existing units, re-proven
  // here against REAL v3 data rather than LoaderMigrationTest's synthetic fixture).
  // =====================================================================================

  private void assertGenerationAwareSeedsSkipped() {
    // _0016__SeedSettings never inserts its own seed documents over a v3 install - settings is
    // reconciled by this slice's own _0021__V3MigrateSettings instead (see
    // assertSettingsMigrated below), never by the seed unit re-inserting its 7 fresh documents
    // alongside the migrated ones.
    assertThat(collection("settings").countDocuments()).isEqualTo(7);

    // _0017__SeedTaskCatalogue: no FRESH-install seed inserted over the v3 task_templates data -
    // tasks/task_revisions ARE populated by this point, but by _0022/_0034 (the v3->v5 catalogue
    // migration + reconciliation), asserted in detail in assertTaskCatalogueMigrated() below.
    assertThat(collection("tasks").countDocuments()).isGreaterThan(0);
    assertThat(collection("task_revisions").countDocuments()).isGreaterThan(0);

    // _0018__SeedTemplates itself never inserts its two starter templates on a v3 install (no
    // FRESH-install seed content lands here) - but workflow_templates is NOT empty: Batch D's
    // _0024__V3ExtractWorkflowTemplates has, by this point in the same migration run, already
    // extracted the real v3 scope=template workflows into this collection (asserted in detail in
    // assertTemplatesExtracted() below) - coincidentally landing on the SAME two _id values
    // _0018's seed would have used (see that unit's collision-guard javadoc), which is exactly
    // why this count is 2 and not 0.
    assertThat(collection("workflow_templates").countDocuments()).isEqualTo(2);
    assertThat(collection("integration_templates").countDocuments()).isZero();

    // _0013/_0014/_0015 are NOT v3-skipped - the graph root, system workspace, and roles are
    // seeded exactly as on a fresh/v4 install, same as LoaderMigrationTest proves.
    assertThat(collection("rel_nodes").find(Filters.eq("_id", "root:root")).first()).isNotNull();
    assertThat(collection("teams").find(Filters.eq("name", "system")).first()).isNotNull();
    assertThat(collection("roles").countDocuments()).isGreaterThanOrEqualTo(5);
  }

  // =====================================================================================
  // _0021__V3MigrateSettings invariants
  // =====================================================================================

  private void assertSettingsMigrated() {
    // The "users" (User Defaults) document has no v5 equivalent - deleted outright.
    assertThat(collection("settings").find(Filters.eq("_id", new ObjectId("6123c1e20b07a54cdce637c0"))).first())
        .as("User Defaults settings document must be deleted")
        .isNull();

    // Exactly 7 documents remain (8 v3 minus the deleted "users" one), under the v5 seed's keys -
    // proves the v3 documents were migrated in place rather than left under their v3 keys or
    // duplicated alongside a fresh seed insert.
    assertThat(collection("settings").countDocuments()).isEqualTo(7);
    List<String> settingsKeys = collection("settings").distinct("key", String.class).into(new ArrayList<>());
    assertThat(settingsKeys)
        .containsExactlyInAnyOrder(
            "task", "workflowrun", "workflow", "features", "teams", "integration", "customizations");

    // None of the 7 surviving documents carry the stale v3 _class discriminator any more -
    // MappingMongoConverter would fail to resolve io.boomerang.mongo.entity.FlowSettingsEntity
    // (not on this classpath) on the next SettingsService read.
    for (Document doc : collection("settings").find()) {
      assertThat(doc.containsKey("_class")).as("no settings document keeps its v3 _class").isFalse();
    }

    // task (v3 "controller"): config keys renamed per legacy 4020.
    Document task = collection("settings").find(Filters.eq("_id", new ObjectId("5f32cb19d09662744c0df51d"))).first();
    assertThat(task.getString("name")).isEqualTo("Task Configuration");
    assertThat(configKeys(task))
        .containsExactlyInAnyOrder("debug", "default.image", "deletion.policy", "edit.verified", "default.timeout");

    // workflowrun (v3 "activity"): key rename only.
    Document workflowrun =
        collection("settings").find(Filters.eq("_id", new ObjectId("60245957226920beece4fdf9"))).first();
    assertThat(workflowrun.getString("key")).isEqualTo("workflowrun");

    // integration (v3 "extensions"): renamed + GitHub config appended (github.appId/pem/appName),
    // while the pre-existing (operator-set) slack.* keys survive untouched - including
    // slack.installURL, which the v5 seed shape does not model at all (documented divergence).
    Document integration =
        collection("settings").find(Filters.eq("_id", new ObjectId("62a7bec0a6166d30aff64a5b"))).first();
    assertThat(integration.getString("name")).isEqualTo("Integration Configuration");
    assertThat(configKeys(integration))
        .contains("github.appId", "github.pem", "github.appName", "slack.token", "slack.installURL");

    // teams: quota keys renamed to their max.workflow*/max.workflowrun* v5 names, plus the new
    // max.workflowrun.storage entry legacy 4039 introduced.
    Document teams = collection("settings").find(Filters.eq("_id", new ObjectId("61393f5966c5eea103dfe134"))).first();
    assertThat(teams.getString("name")).isEqualTo("Team Quotas");
    assertThat(configKeys(teams))
        .containsExactlyInAnyOrder(
            "max.workflowrun.concurrent",
            "max.workflow.count",
            "max.workflowrun.monthly",
            "max.workflowrun.duration",
            "max.workflow.storage",
            "max.workflowrun.storage");
    Document newStorageEntry =
        configOf(teams).stream().filter(c -> "max.workflowrun.storage".equals(c.getString("key"))).findFirst().get();
    assertThat(newStorageEntry.getString("value")).isEqualTo("2Gi");

    // features: workflowQuotas -> teamQuotas.
    Document features =
        collection("settings").find(Filters.eq("_id", new ObjectId("612904d60b07a54cdc4dc6a9"))).first();
    assertThat(configKeys(features)).contains("teamQuotas");
    assertThat(configKeys(features)).doesNotContain("workflowQuotas");
  }

  @SuppressWarnings("unchecked")
  private List<Document> configOf(Document setting) {
    return (List<Document>) setting.get("config");
  }

  private List<String> configKeys(Document setting) {
    List<String> keys = new ArrayList<>();
    for (Document entry : configOf(setting)) {
      keys.add(entry.getString("key"));
    }
    return keys;
  }

  // =====================================================================================
  // _0031__V3MigrateGlobalParameters invariants
  // =====================================================================================

  private void assertGlobalParametersMigrated() {
    // The v3 source collection - real name global_config, NOT v4's wrongly-targeted
    // global_params - is dropped once migrated.
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).as("global_config dropped after migration").doesNotContain(prefixed("global_config"));
    assertThat(names)
        .as("global_params never existed on this dump - nothing for the defensive path to drop")
        .doesNotContain(prefixed("global_params"));

    // The dump's one global_config document (_id 64b9cc1c1e47974fb3116ef2, key "asdasd") landed
    // in "parameters" under its v5 field names, same _id preserved.
    Document param =
        collection("parameters").find(Filters.eq("_id", new ObjectId("64b9cc1c1e47974fb3116ef2"))).first();
    assertThat(param).as("migrated global parameter must exist under its original _id").isNotNull();
    assertThat(param.getString("name")).isEqualTo("asdasd");
    assertThat(param.getString("label")).isEqualTo("asdads");
    assertThat(param.getString("type")).isEqualTo("text");
    assertThat(param.getString("description")).isEqualTo("adads");
    assertThat(param.getString("value")).isEqualTo("adsads");
    assertThat(param.getBoolean("readOnly")).isFalse();
    assertThat(param.containsKey("_class"))
        .as("no stale v3 _class discriminator on the migrated parameter")
        .isFalse();
    assertThat(param.containsKey("key")).as("v3 'key' field renamed away, not carried over").isFalse();
  }

  // =====================================================================================
  // _0022__V3MigrateTasks / _0034__V3ReconcileCatalogue invariants (Batch B)
  // =====================================================================================

  private void assertTaskCatalogueMigrated() {
    // task_templates fully drained into tasks/task_revisions, then dropped.
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).as("task_templates dropped after migration").doesNotContain(prefixed("task_templates"));

    // 89 real v3 task_templates documents, all migrated (none dropped) - see _0022's javadoc.
    // _0034 reconciles the 87-task seed catalogue against them (all 87 already present by legacy
    // _id) and inserts nothing new at the task level; the install's own extra 2 (Kubernetes CLI,
    // Tysons Test Task) are untouched additions.
    assertThat(collection("tasks").countDocuments()).isEqualTo(89);

    // 131 real v3 revisions migrated 1:1, plus exactly one reconciled addition: the seed
    // catalogue's Manual Approval v2, absent from this install's own (older) snapshot.
    assertThat(collection("task_revisions").countDocuments()).isEqualTo(132);

    // Every task_revisions document has a non-null parentRef resolving to an existing task.
    List<String> taskIds = new ArrayList<>();
    for (Document task : collection("tasks").find()) {
      taskIds.add(task.get("_id").toString());
    }
    long populatedParams = 0;
    for (Document revision : collection("task_revisions").find()) {
      String parentRef = revision.getString("parentRef");
      assertThat(parentRef).as("task_revisions.parentRef must be present").isNotNull();
      assertThat(taskIds).as("parentRef %s must resolve to an existing task", parentRef).contains(parentRef);

      // config folded into spec.params and dropped - 4043 squashed in, never left as an
      // intermediate a later unit would need to rewrite.
      assertThat(revision.containsKey("config")).as("legacy 'config' must not survive migration").isFalse();
      Document spec = (Document) revision.get("spec");
      assertThat(spec).as("spec must be present").isNotNull();
      assertThat(spec.containsKey("params")).as("spec.params must be present (possibly empty)").isTrue();
      @SuppressWarnings("unchecked")
      List<Document> params = (List<Document>) spec.get("params");
      populatedParams += params.size();
    }
    assertThat(populatedParams).as("spec.params must be populated overall, not left empty").isGreaterThan(0);

    // Spot check: the well-known "sleep" system task matches the seeded shape (see _0022's
    // javadoc for why this needs the documented 3-field override rather than the generic
    // nodetype mapping - legacy 4010's hardcode, verified against the real dump).
    Document sleepTask =
        collection("tasks").find(Filters.eq("_id", new ObjectId("5bd97bea5a5df954ad592c06"))).first();
    assertThat(sleepTask).isNotNull();
    assertThat(sleepTask.getString("name")).isEqualTo("sleep");
    assertThat(sleepTask.getString("type")).isEqualTo("sleep");
    assertThat(sleepTask.getString("status")).isEqualTo("active");
    assertThat(sleepTask.getBoolean("verified")).isTrue();

    Document sleepRevision =
        collection("task_revisions")
            .find(Filters.eq("parentRef", "5bd97bea5a5df954ad592c06"))
            .first();
    assertThat(sleepRevision).as("sleep task must have exactly one migrated revision").isNotNull();
    assertThat(sleepRevision.getInteger("version")).isEqualTo(1);
    assertThat(sleepRevision.getString("displayName")).isEqualTo("Sleep");
    assertThat(sleepRevision.getString("category")).isEqualTo("Workflow");
    assertThat(sleepRevision.getString("icon")).isEqualTo("Power on/off");
    assertThat(sleepRevision.getString("description"))
        .isEqualTo("Sleep for specified duration in milliseconds");
    Document sleepChangelog = (Document) sleepRevision.get("changelog");
    assertThat(sleepChangelog.getString("author")).isEqualTo("608ca23d70bfa94ac91f8eef");
    assertThat(sleepChangelog.containsKey("userName")).as("changelog userName (PII) must be dropped").isFalse();
    Document sleepSpec = (Document) sleepRevision.get("spec");
    assertThat(sleepSpec.getList("arguments", String.class))
        .as("sleep's real v3 arguments (system/sleep) are overridden per legacy 4010")
        .isEmpty();
    @SuppressWarnings("unchecked")
    List<Document> sleepParams = (List<Document>) sleepSpec.get("params");
    assertThat(sleepParams).hasSize(1);
    Document durationParam = sleepParams.get(0);
    assertThat(durationParam.getString("name")).isEqualTo("duration");
    assertThat(durationParam.getString("label")).isEqualTo("Duration");
    assertThat(durationParam.getString("type")).isEqualTo("text");
    assertThat(durationParam.containsKey("defaultValue"))
        .as("v3's duration config never carried a defaultValue - none should be invented")
        .isFalse();

    // Manual Approval: the install's own v1 survives untouched, and _0034 reconciled the
    // missing seeded v2 (real gap found comparing the dump to the seed catalogue).
    long approvalRevisions =
        collection("task_revisions")
            .countDocuments(Filters.eq("parentRef", "5f6379c974f51934044cbbd6"));
    assertThat(approvalRevisions).isEqualTo(2);
  }

  // =====================================================================================
  // _0026__V3MigrateTaskRunRefs invariants (Batch B)
  // =====================================================================================

  private void assertTaskRunRefsUnitIsNoOp() {
    // v3 task activity lives in workflows_activity_task (dropped by _0020) - the dump carries no
    // task_runs data at all (the collection exists only because _0002's index-ensure implicitly
    // creates it, empty, on every install), so this unit has nothing to migrate here. Its
    // correctness (the 4033 taskVersion-bug fix + templateRef->taskRef resolution) is exercised
    // in LoaderMigrationTest for an install that does carry task_runs documents.
    assertThat(collection("task_runs").countDocuments())
        .as("no real v3 task_runs data exists on this dump")
        .isZero();
  }

  // =====================================================================================
  // _0027__V3MigrateWorkspaces invariants (Batch C)
  // =====================================================================================

  private void assertWorkspacesMigrated() {
    // 28 real v3 teams + the 1 seeded "system" workspace (_0014, untouched by this batch) + one
    // personal workspace per real v3 user (57, see assertUsersMigrated/_0028) = 86.
    assertThat(collection("teams").countDocuments()).isEqualTo(86);

    // The seeded system workspace is untouched: still matched by name, still v5-shaped exactly
    // as _0014 wrote it (unlimited quotas, type "system"), never touched by this v3-only batch.
    Document system = collection("teams").find(Filters.eq("name", "system")).first();
    assertThat(system).as("seeded system workspace must survive the v3 batch untouched").isNotNull();
    assertThat(system.getString("type")).isEqualTo("system");
    assertThat(system.getString("status")).isEqualTo("active");
    Document systemQuotas = (Document) system.get("quotas");
    assertThat(systemQuotas.getInteger("maxWorkflowCount")).isEqualTo(Integer.MAX_VALUE);
    assertThat(systemQuotas.getInteger("maxWorkflowRunStorage")).isEqualTo(Integer.MAX_VALUE);

    // The 28 migrated v3 teams: type "hobby" (the documented honest default - v3 had no tier
    // concept), every one has a slug "name" and a display "displayName", v5-named quota fields
    // only (never the legacy maxWorkflowExecutionMonthly/maxWorkflowExecutionTime/
    // maxConcurrentWorkflows names), and no leftover v3 "_class" discriminator.
    List<Document> migratedTeams =
        collection("teams").find(Filters.eq("type", "hobby")).into(new ArrayList<>());
    assertThat(migratedTeams).hasSize(28);
    for (Document workspace : migratedTeams) {
      assertThat(workspace.getString("name"))
          .as("every migrated workspace must have a non-blank slug name")
          .isNotBlank();
      assertThat(workspace.getString("name")).isEqualTo(workspace.getString("name").toLowerCase());
      assertThat(workspace.getString("displayName")).as("displayName must survive").isNotBlank();
      assertThat(workspace.containsKey("_class")).as("no leftover v3 _class").isFalse();
      assertThat(workspace.containsKey("isActive")).as("v3 isActive must not survive").isFalse();
      assertThat(workspace.containsKey("higherLevelGroupId"))
          .as("v3 higherLevelGroupId must not survive under its own name")
          .isFalse();
      assertThat(workspace.containsKey("settings")).as("v3 settings must not survive").isFalse();
      assertThat(workspace.containsKey("approverGroups"))
          .as("v3 approverGroups must not survive on the workspace document")
          .isFalse();
      Document quotas = (Document) workspace.get("quotas");
      assertThat(quotas).as("quotas must be present").isNotNull();
      assertThat(quotas.keySet())
          .as("quotas must use v5 field names exclusively")
          .containsExactlyInAnyOrder(
              "maxWorkflowCount",
              "maxWorkflowRunMonthly",
              "maxWorkflowStorage",
              "maxWorkflowRunStorage",
              "maxWorkflowRunDuration",
              "maxConcurrentRuns");
      // The new field with no v3 source gets the documented default (2, matching the migrated
      // teams settings document's max.workflowrun.storage numeric value).
      assertThat(quotas.getInteger("maxWorkflowRunStorage")).isEqualTo(2);
    }

    // Spot check a specific known team (SRC Innovations) end to end.
    Document src =
        collection("teams").find(Filters.eq("_id", new ObjectId("615428b57162702bd3ee7605"))).first();
    assertThat(src).isNotNull();
    assertThat(src.getString("displayName")).isEqualTo("SRC Innovations");
    assertThat(src.getString("name")).isEqualTo("src-innovations");
    assertThat(src.getString("status")).isEqualTo("active");
    assertThat(src.getString("externalRef")).isEqualTo("615428b57162702bd3ee7605");
    Document srcQuotas = (Document) src.get("quotas");
    assertThat(srcQuotas.getInteger("maxWorkflowCount")).isEqualTo(200);
    assertThat(srcQuotas.getInteger("maxWorkflowRunMonthly")).isEqualTo(1000);
    assertThat(srcQuotas.getInteger("maxWorkflowStorage")).isEqualTo(4);
    assertThat(srcQuotas.getInteger("maxWorkflowRunDuration")).isEqualTo(240);
    assertThat(srcQuotas.getInteger("maxConcurrentRuns")).isEqualTo(10);

    // 24 of the 28 real teams are isActive:false -> status "inactive".
    assertThat(collection("teams").countDocuments(Filters.and(Filters.eq("type", "hobby"), Filters.eq("status", "inactive"))))
        .isEqualTo(24);
    assertThat(collection("teams").countDocuments(Filters.and(Filters.eq("type", "hobby"), Filters.eq("status", "active"))))
        .isEqualTo(4);

    // Team Tyson: the one real team with a populated settings.properties[] entry - proves the
    // 4044 key->name / values->value squash actually ran (its source already carries singular
    // "value", so the values->value branch is a documented no-op here, but "key" must still be
    // gone and "name" present).
    Document teamTyson =
        collection("teams").find(Filters.eq("_id", new ObjectId("61551ffaa1747c3cd93f4bed"))).first();
    assertThat(teamTyson).isNotNull();
    @SuppressWarnings("unchecked")
    List<Document> parameters = (List<Document>) teamTyson.get("parameters");
    assertThat(parameters).hasSize(1);
    Document param = parameters.get(0);
    assertThat(param.getString("name")).isEqualTo("team-test-param");
    assertThat(param.containsKey("key")).as("v3 'key' renamed away").isFalse();
    assertThat(param.getString("value")).isEqualTo("wiggles");
    assertThat(param.containsKey("_id")).as("the v3 property's own random _id must be dropped").isFalse();

    // approverGroups: present (empty) on 2 real teams (SRC Innovations, Uvis Team) - both extract
    // to nothing, so approver_groups stays empty on this dump. Unpopulated but exercised: no
    // NullPointerException, no document inserted for an empty array.
    assertThat(collection("approver_groups").countDocuments())
        .as("both real teams carrying approverGroups[] have it empty - nothing to extract")
        .isZero();
  }

  // =====================================================================================
  // _0028__V3MigrateUsers invariants (Batch C)
  // =====================================================================================

  private void assertUsersMigrated() {
    assertThat(collection("users").countDocuments()).isEqualTo(57);

    for (Document user : collection("users").find()) {
      assertThat(user.containsKey("_class")).as("no leftover v3 _class on any user").isFalse();
      assertThat(user.containsKey("quotas")).as("v3 per-user quotas must be dropped").isFalse();
      assertThat(user.containsKey("flowTeams")).as("v3 flowTeams must be dropped").isFalse();
      assertThat(user.containsKey("firstLoginDate"))
          .as("v3 firstLoginDate renamed away, not carried over")
          .isFalse();
      assertThat(user.getDate("creationDate")).as("creationDate must always be set").isNotNull();
      Document settings = (Document) user.get("settings");
      assertThat(settings).as("settings must be present").isNotNull();
      assertThat(settings.getBoolean("isShowHelp"))
          .as("isShowHelp has no v3 source - defaults true")
          .isTrue();
    }

    // Spot check: Tyson (admin, has firstLoginDate).
    Document tyson =
        collection("users").find(Filters.eq("_id", new ObjectId("614415021950a72949b00efb"))).first();
    assertThat(tyson).isNotNull();
    assertThat(tyson.getString("email")).isEqualTo("tyson@lawrie.com.au");
    assertThat(tyson.getString("name")).isEqualTo("Tyson");
    assertThat(tyson.getString("type")).isEqualTo("admin");
    assertThat(tyson.getString("status")).isEqualTo("active");
    Document tysonSettings = (Document) tyson.get("settings");
    assertThat(tysonSettings.getBoolean("isFirstVisit")).isTrue();
    assertThat(tysonSettings.getBoolean("hasConsented")).isFalse();
    Map<String, Object> tysonLabels = (Map<String, Object>) tyson.get("labels");
    assertThat(tysonLabels).containsEntry("slack_app_opened", "true");

    // Every user has exactly one personal workspace, discoverable via type=personal +
    // externalRef=<userId> (the linkage _0028 leaves for Batch E to consume) - and none other.
    long personalWorkspaces = collection("teams").countDocuments(Filters.eq("type", "personal"));
    assertThat(personalWorkspaces).isEqualTo(57);
    for (Document user : collection("users").find()) {
      String userId = user.get("_id").toString();
      List<Document> owned =
          collection("teams")
              .find(Filters.and(Filters.eq("type", "personal"), Filters.eq("externalRef", userId)))
              .into(new ArrayList<>());
      assertThat(owned)
          .as("user %s must own exactly one personal workspace", userId)
          .hasSize(1);
      Document personal = owned.get(0);
      assertThat(personal.getString("name")).isNotBlank();
      assertThat(personal.getString("displayName")).endsWith("Personal Team");
      Document quotas = (Document) personal.get("quotas");
      assertThat(quotas.getInteger("maxWorkflowCount")).isEqualTo(10);
      assertThat(quotas.getInteger("maxWorkflowRunMonthly")).isEqualTo(20);
      assertThat(quotas.getInteger("maxWorkflowStorage")).isEqualTo(25);
      assertThat(quotas.getInteger("maxWorkflowRunStorage")).isEqualTo(2);
      assertThat(quotas.getInteger("maxWorkflowRunDuration")).isEqualTo(30);
      assertThat(quotas.getInteger("maxConcurrentRuns")).isEqualTo(4);
    }

    // Spot check Tyson's own personal workspace naming - reproduces 4014's derivation literally.
    Document tysonPersonal =
        collection("teams")
            .find(
                Filters.and(
                    Filters.eq("type", "personal"),
                    Filters.eq("externalRef", "614415021950a72949b00efb")))
            .first();
    assertThat(tysonPersonal).isNotNull();
    assertThat(tysonPersonal.getString("displayName")).isEqualTo("Tyson Personal Team");
    assertThat(tysonPersonal.getString("name")).isEqualTo("tyson-personal-team");
    assertThat(tysonPersonal.getString("status")).isEqualTo("active");

    // A user whose v3 "name" is itself an email (admin@flowabl.io) exercises the @/. -> "-"
    // replacement 4014 applied before slugifying.
    Document adminUser = collection("users").find(Filters.eq("email", "admin@flowabl.io")).first();
    assertThat(adminUser).isNotNull();
    Document adminPersonal =
        collection("teams")
            .find(
                Filters.and(
                    Filters.eq("type", "personal"),
                    Filters.eq("externalRef", adminUser.get("_id").toString())))
            .first();
    assertThat(adminPersonal).isNotNull();
    assertThat(adminPersonal.getString("displayName")).isEqualTo("admin-flowabl-io Personal Team");
    assertThat(adminPersonal.getString("name")).isEqualTo("admin-flowabl-io-personal-team");
  }

  // =====================================================================================
  // _0023__V3MigrateWorkflows invariants (Batch D)
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private void assertWorkflowsMigrated(long workflowsBefore) {
    // 67 real v3 workflows minus the 2 scope=template ones _0024 extracts and deletes.
    assertThat(collection("workflows").countDocuments()).isEqualTo(workflowsBefore - 2);
    assertThat(collection("workflows").countDocuments()).isEqualTo(65);

    for (Document workflow : collection("workflows").find()) {
      assertThat(workflow.containsKey("_class")).as("no leftover v3 _class on any workflow").isFalse();
      assertThat(workflow.containsKey("tokens")).as("v3 tokens must be dropped").isFalse();
      assertThat(workflow.containsKey("flowTeamId")).as("v3 flowTeamId dropped by name").isFalse();
      assertThat(workflow.containsKey("ownerUserId")).as("v3 ownerUserId dropped by name").isFalse();
      assertThat(workflow.containsKey("shortDescription")).as("v3 shortDescription must not survive").isFalse();
      assertThat(workflow.containsKey("storage")).as("v3 storage must not survive on the workflow").isFalse();
      assertThat(workflow.getString("displayName")).as("displayName must survive").isNotBlank();
      assertThat(workflow.getString("name")).isEqualTo(workflow.getString("name").toLowerCase());
      assertThat(workflow.get("creationDate")).as("creationDate must always be set").isNotNull();
      Document triggers = (Document) workflow.get("triggers");
      assertThat(triggers).as("triggers must always be built, even defensively").isNotNull();
      assertThat(triggers.keySet()).containsExactlyInAnyOrder("manual", "schedule", "webhook", "event");
    }

    // Spot check: "Better Uptime Heartbeat" (scope=system - no ownerRef), the workflow used
    // throughout this section's other spot checks too.
    Document heartbeat =
        collection("workflows").find(Filters.eq("_id", new ObjectId("6144265f1950a72949b00efc"))).first();
    assertThat(heartbeat).isNotNull();
    assertThat(heartbeat.getString("displayName")).isEqualTo("Better Uptime Heartbeat");
    assertThat(heartbeat.getString("name")).isEqualTo("better-uptime-heartbeat");
    assertThat(heartbeat.getString("description")).isEqualTo("This does a daily heart beat check with Better Update");
    assertThat(heartbeat.getString("status")).isEqualTo("active");
    assertThat(heartbeat.getString("scope")).isEqualTo("system");
    assertThat(heartbeat.containsKey("ownerRef")).as("system-scope workflow has no owner").isFalse();
    Document heartbeatTriggers = (Document) heartbeat.get("triggers");
    assertThat(((Document) heartbeatTriggers.get("manual")).getBoolean("enabled")).isTrue();
    assertThat(((Document) heartbeatTriggers.get("schedule")).getBoolean("enabled")).isTrue();
    assertThat(((Document) heartbeatTriggers.get("webhook")).getBoolean("enabled")).isFalse();
    assertThat(((Document) heartbeatTriggers.get("event")).getBoolean("enabled")).isFalse();

    // Spot check: "Approval Generator" (scope=team) - proves the scope/ownerRef extra-field
    // discoverability _0023's javadoc documents, and the storage->workspaces[] (both enabled)
    // move onto the revision rather than the workflow.
    Document approvalGenerator =
        collection("workflows").find(Filters.eq("_id", new ObjectId("6437a414ec19f9693daab0c2"))).first();
    assertThat(approvalGenerator).isNotNull();
    assertThat(approvalGenerator.getString("name")).isEqualTo("approval-generator");
    assertThat(approvalGenerator.getString("scope")).isEqualTo("team");
    assertThat(approvalGenerator.getString("ownerRef")).isEqualTo("61551ffaa1747c3cd93f4bed");

    // 151 real v3 revisions minus the 2 belonging to the extracted template workflows.
    assertThat(collection("workflow_revisions").countDocuments()).isEqualTo(149);
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).as("workflows_revisions dropped after migration").doesNotContain(prefixed("workflows_revisions"));

    // The headline fix: every migrated task that carries a taskRef must carry a non-null
    // taskVersion (legacy 4005/4034 lost this on every real v4 install - see _0023's javadoc).
    long tasksWithTaskRef = 0;
    for (Document revision : collection("workflow_revisions").find()) {
      for (Document task : (List<Document>) revision.get("tasks")) {
        if (task.containsKey("taskRef")) {
          tasksWithTaskRef++;
          assertThat(task.get("taskVersion"))
              .as("task %s (revision %s) must carry a non-null taskVersion", task.get("name"), revision.get("_id"))
              .isNotNull();
        }
      }
    }
    // 287 real dag tasks carry a templateId/taskRef in total, minus the 12 (8+4) belonging to the
    // two scope=template workflows' revisions - those are extracted into workflow_templates by
    // _0024 (and asserted there instead - see assertTemplatesExtracted).
    assertThat(tasksWithTaskRef).as("287 - 12 template-extracted = 275 remain on workflow_revisions").isEqualTo(275);

    // Detailed spot check: revision 6153a5be7162702bd3ee75c2 (Account Management - Onboarding,
    // version 6) - exercises taskRef/taskVersion resolution, dependency taskRef resolution
    // (including the "start" special case), and decisionCondition (both the "" default and a real
    // switchCondition value) all in one real, non-trivial DAG.
    Document onboardingRevision =
        collection("workflow_revisions").find(Filters.eq("_id", new ObjectId("6153a5be7162702bd3ee75c2"))).first();
    assertThat(onboardingRevision).isNotNull();
    assertThat(onboardingRevision.getString("workflowRef")).isEqualTo("61454e2d1000b141daa8f85f");
    assertThat(onboardingRevision.getInteger("version")).isEqualTo(6);
    List<Document> onboardingTasks = (List<Document>) onboardingRevision.get("tasks");
    assertThat(onboardingTasks).hasSize(5);

    Document sendEmail = taskNamed(onboardingTasks, "Send Welcome Email");
    assertThat(sendEmail.getString("type")).isEqualTo("template");
    assertThat(sendEmail.getString("taskRef")).isEqualTo("612989ab63a78c0c37074e91");
    assertThat(sendEmail.getInteger("taskVersion")).isEqualTo(1);
    assertThat((List<Document>) sendEmail.get("params")).hasSize(8);
    List<Document> sendEmailDeps = (List<Document>) sendEmail.get("dependencies");
    assertThat(sendEmailDeps).hasSize(1);
    assertThat(sendEmailDeps.get(0).getString("taskRef")).isEqualTo("start");
    assertThat(sendEmailDeps.get(0).getString("decisionCondition")).isEqualTo("");

    Document switch1 = taskNamed(onboardingTasks, "Switch 1");
    assertThat(switch1.getString("type")).isEqualTo("decision");
    assertThat(switch1.getString("taskRef")).isEqualTo("5c37af285616d5f3544568fd");
    assertThat(switch1.getInteger("taskVersion")).isEqualTo(1);

    Document createTeam = taskNamed(onboardingTasks, "Create Team");
    assertThat(createTeam.getString("type")).isEqualTo("template");
    assertThat(createTeam.getString("taskRef")).isEqualTo("5c3d0401352b1b514150545b");
    assertThat(createTeam.getInteger("taskVersion")).isEqualTo(4);
    assertThat((List<Document>) createTeam.get("params")).hasSize(7);
    List<Document> createTeamDeps = (List<Document>) createTeam.get("dependencies");
    assertThat(createTeamDeps).hasSize(1);
    assertThat(createTeamDeps.get(0).getString("taskRef")).isEqualTo("Switch 1");
    // A real v3 switchCondition ("true") - proves decisionCondition <- switchCondition, not
    // always the "" default.
    assertThat(createTeamDeps.get(0).getString("decisionCondition")).isEqualTo("true");
    assertThat(createTeamDeps.get(0).containsKey("taskId")).isFalse();
    assertThat(createTeamDeps.get(0).containsKey("switchCondition")).isFalse();
    assertThat(createTeamDeps.get(0).containsKey("conditionalExecution")).isFalse();
    // Visual edge-routing metadata is intentionally retained (matches legacy's own commented-out
    // remove, and the seeded workflow-templates.json reference shape) - see _0023's javadoc.
    assertThat(createTeamDeps.get(0).containsKey("metadata")).isTrue();

    Document start = taskNamed(onboardingTasks, "start");
    assertThat(start.getString("type")).isEqualTo("start");
    assertThat(start.containsKey("taskRef")).isFalse();
    Document end = taskNamed(onboardingTasks, "end");
    assertThat(end.getString("type")).isEqualTo("end");
    assertThat((List<Document>) end.get("dependencies")).hasSize(3);
  }

  @SuppressWarnings("unchecked")
  private Document taskNamed(List<Document> tasks, String name) {
    return tasks.stream()
        .filter(t -> name.equals(t.getString("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No task named " + name));
  }

  // =====================================================================================
  // _0024__V3ExtractWorkflowTemplates invariants (Batch D)
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private void assertTemplatesExtracted() {
    assertThat(collection("workflow_templates").countDocuments()).isEqualTo(2);

    // The two source workflows/revisions - collision-guard ids the batch instructions and
    // _0018__SeedTemplates both name - are gone from their v3-shaped collections.
    assertThat(collection("workflows").find(Filters.eq("_id", new ObjectId("62be6a3266ff43491f09d2e7"))).first())
        .as("source template workflow must be deleted")
        .isNull();
    assertThat(collection("workflows").find(Filters.eq("_id", new ObjectId("62be6a3e66ff43491f09d2e9"))).first())
        .as("source template workflow must be deleted")
        .isNull();

    Document planets =
        collection("workflow_templates").find(Filters.eq("_id", new ObjectId("62be6a3266ff43491f09d2e8"))).first();
    assertThat(planets).isNotNull();
    assertThat(planets.getString("name")).isEqualTo("looking-through-planets-with-http-call");
    // The trailing space in the real v3 name is preserved verbatim on displayName - matches the
    // seeded workflow-templates.json reference byte-for-byte (see _0024's javadoc).
    assertThat(planets.getString("displayName")).isEqualTo("Looking through planets with HTTP Call ");
    assertThat(planets.getString("icon")).isEqualTo("bot");
    // 8 real dag tasks on this template carry a templateId/taskRef (the other 4 of the 12 total
    // template-extracted tasks belong to mongoQuery below) - together with the 275 left on
    // workflow_revisions (see assertWorkflowsMigrated), these account for all 287 real dag tasks
    // that carry a templateId.
    @SuppressWarnings("unchecked")
    List<Document> planetsTasks = (List<Document>) planets.get("tasks");
    long planetsWithTaskRef = planetsTasks.stream().filter(t -> t.containsKey("taskRef")).count();
    assertThat(planetsWithTaskRef).isEqualTo(8);

    Document mongoQuery =
        collection("workflow_templates").find(Filters.eq("_id", new ObjectId("62be6a3e66ff43491f09d2ea"))).first();
    assertThat(mongoQuery).isNotNull();
    assertThat(mongoQuery.getString("name")).isEqualTo("mongodb-email-query-results");
    assertThat(mongoQuery.getString("displayName")).isEqualTo("MongoDB email query results");
    assertThat(mongoQuery.getInteger("version")).isEqualTo(1);
    List<Document> mongoTasks = (List<Document>) mongoQuery.get("tasks");
    assertThat(mongoTasks).hasSize(6);
    // The same headline taskVersion fix survives extraction into workflow_templates - and
    // deliberately DIFFERS from the static seed/workflow-templates.json reference (whose
    // taskVersion is null on every task, itself a fossil of the same v4 bug, captured by running
    // the OLD buggy loader) - proof the fix reaches all the way through this batch's output.
    Document executeQuery = taskNamed(mongoTasks, "MongoDB Execute Query");
    assertThat(executeQuery.getString("taskRef")).isEqualTo("620636845b676b358e8c440c");
    assertThat(executeQuery.getInteger("taskVersion")).isEqualTo(1);
    Document displayResults = taskNamed(mongoTasks, "Display the results");
    assertThat(displayResults.getInteger("taskVersion")).isEqualTo(2);
    Document sendAttachment = taskNamed(mongoTasks, "Send the results as attachemnt");
    assertThat(sendAttachment.getInteger("taskVersion")).isEqualTo(3);
    Document failedEmail = taskNamed(mongoTasks, "Failed to run the query email");
    assertThat(failedEmail.getInteger("taskVersion")).isEqualTo(3);

    // 4046's merge - single param, matches the seeded reference shape exactly (see _0024's
    // javadoc: config-as-base, defaultValue/description merged in from the naive param).
    List<Document> mongoParams = (List<Document>) mongoQuery.get("params");
    assertThat(mongoParams).hasSize(1);
    Document queryParam = mongoParams.get(0);
    assertThat(queryParam.getString("name")).isEqualTo("query");
    assertThat(queryParam.getString("label")).isEqualTo("query");
    assertThat(queryParam.getString("type")).isEqualTo("textarea");
    assertThat(queryParam.getBoolean("required")).isTrue();
    assertThat(queryParam.getString("defaultValue")).startsWith("DBQuery.shellBatchSize");
    assertThat(queryParam.containsKey("workflowRef")).isFalse();
  }

  // =====================================================================================
  // _0025__V3MigrateRuns invariants (Batch D) - workflows_activity -> workflow_runs
  // =====================================================================================

  private void assertRunsMigrated() {
    assertThat(collection("workflow_runs").countDocuments()).isEqualTo(18093);
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).as("workflows_activity dropped after migration").doesNotContain(prefixed("workflows_activity"));

    // Status mapping distribution across all 18093 real runs.
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("status", "succeeded"))).isEqualTo(18065);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("status", "cancelled"))).isEqualTo(13);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("status", "failed"))).isEqualTo(8);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("status", "invalid"))).isEqualTo(4);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("status", "running"))).isEqualTo(3);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("phase", "finalized"))).isEqualTo(18093);

    // trigger mapping - "scheduler" renamed to "schedule" (see _0025's javadoc).
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("trigger", "schedule"))).isEqualTo(17699);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("trigger", "manual"))).isEqualTo(176);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("trigger", "custom"))).isEqualTo(113);
    assertThat(collection("workflow_runs").countDocuments(Filters.eq("trigger", "webhook"))).isEqualTo(105);

    // THE 4002 FIX - initiatedByRef was computed and discarded by legacy; written here. Exactly
    // the 176 real runs that carry a v3 initiatedByUserId get one.
    assertThat(collection("workflow_runs").countDocuments(Filters.exists("initiatedByRef"))).isEqualTo(176);

    // Spot check: a straightforward manual/succeeded run.
    Document run =
        collection("workflow_runs").find(Filters.eq("_id", new ObjectId("614427071950a72949b00eff"))).first();
    assertThat(run).isNotNull();
    assertThat(run.getString("workflowRef")).isEqualTo("6144265f1950a72949b00efc");
    assertThat(run.getString("workflowRevisionRef")).isEqualTo("614427031950a72949b00efe");
    assertThat(run.getString("status")).isEqualTo("succeeded");
    assertThat(run.getString("phase")).isEqualTo("finalized");
    assertThat(run.getString("trigger")).isEqualTo("manual");
    assertThat(run.getString("initiatedByRef")).isEqualTo("614415021950a72949b00efb");
    assertThat(run.getLong("duration")).isEqualTo(28832L);
    assertThat(run.getInteger("workflowVersion")).isEqualTo(0);
    assertThat(run.getBoolean("isAwaitingApproval")).isFalse();
    assertThat(run.getString("scope")).isEqualTo("system");

    // Spot check: statusMessage direct passthrough (a real "invalid" run).
    Document invalidRun =
        collection("workflow_runs").find(Filters.eq("_id", new ObjectId("618dc495c87e7471580d099d"))).first();
    assertThat(invalidRun).isNotNull();
    assertThat(invalidRun.getString("statusMessage")).isEqualTo("Failed to run workflow: Incomplete workflow");

    // Spot check: params extraction from a real run with properties[].
    Document runWithParams =
        collection("workflow_runs").find(Filters.eq("_id", new ObjectId("61637b7ba1747c3cd93f5024"))).first();
    assertThat(runWithParams).isNotNull();
    @SuppressWarnings("unchecked")
    List<Document> runParams = (List<Document>) runWithParams.get("params");
    assertThat(runParams).hasSize(2);
    assertThat(runParams).anySatisfy(p -> {
      assertThat(p.getString("name")).isEqualTo("email");
      assertThat(p.getString("value")).isEqualTo("tyson@lawrie.com.au");
    });

    // Spot check: labels array -> map.
    Document runWithLabels =
        collection("workflow_runs").find(Filters.eq("_id", new ObjectId("614555131000b141daa8f869"))).first();
    assertThat(runWithLabels).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, String> runLabels = (Map<String, String>) runWithLabels.get("labels");
    assertThat(runLabels).containsEntry("eventId", "30eee071-00eb-47d5-b897-af19ab548f79");
  }

  // =====================================================================================
  // _0025__V3MigrateRuns invariants (Batch D) - workflows_activity_approval -> actions
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private void assertActionsMigrated() {
    assertThat(collection("actions").countDocuments()).isEqualTo(8);
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names)
        .as("workflows_activity_approval dropped after migration")
        .doesNotContain(prefixed("workflows_activity_approval"));

    Document approved =
        collection("actions").find(Filters.eq("_id", new ObjectId("61543b487162702bd3ee7624"))).first();
    assertThat(approved).isNotNull();
    assertThat(approved.getString("workflowRef")).isEqualTo("61543b087162702bd3ee761e");
    assertThat(approved.getString("workflowRunRef")).isEqualTo("61543b487162702bd3ee7621");
    assertThat(approved.getString("taskRunRef")).isEqualTo("61543b487162702bd3ee7623");
    assertThat(approved.getString("status")).isEqualTo("approved");
    assertThat(approved.getString("type")).isEqualTo("approval");
    assertThat(approved.getInteger("numberOfApprovers")).isEqualTo(1);
    List<Document> actioners = (List<Document>) approved.get("actioners");
    assertThat(actioners).hasSize(1);
    assertThat(actioners.get(0).getString("approverId")).isEqualTo("614415021950a72949b00efb");
    assertThat(actioners.get(0).getBoolean("approved")).isTrue();
    assertThat(actioners.get(0).get("date")).as("actionDate -> date").isNotNull();

    // type "task" -> "manual" (legacy 4003).
    Document taskType =
        collection("actions").find(Filters.eq("_id", new ObjectId("61543b487162702bd3ee7625"))).first();
    assertThat(taskType).isNotNull();
    assertThat(taskType.getString("type")).isEqualTo("manual");
    assertThat(taskType.getString("status")).isEqualTo("rejected");

    // No actioners at all in the v3 source (submitted, not yet actioned) - must be an empty list,
    // not null/absent.
    Document submitted =
        collection("actions").find(Filters.eq("_id", new ObjectId("630ef94ad5ff537e6cfad63d"))).first();
    assertThat(submitted).isNotNull();
    assertThat(submitted.getString("status")).isEqualTo("submitted");
    assertThat((List<Document>) submitted.get("actioners")).isEmpty();
  }

  // =====================================================================================
  // _0025__V3MigrateRuns invariants (Batch D) - workflows_schedules -> workflow_schedules
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private void assertSchedulesMigrated() {
    assertThat(collection("workflow_schedules").countDocuments()).isEqualTo(90);
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names)
        .as("workflows_schedules dropped after migration")
        .doesNotContain(prefixed("workflows_schedules"));

    for (Document schedule : collection("workflow_schedules").find()) {
      assertThat(schedule.containsKey("parameters"))
          .as("FIX: the legacy 4017 'properties' stray-removal bug left this key behind - a fresh"
              + " v5 document never carries it")
          .isFalse();
      assertThat(schedule.containsKey("cronSchedlue")).as("v3 typo'd key must not survive").isFalse();
      assertThat(schedule.getString("workflowRef")).as("workflowRef must always be resolvable").isNotBlank();
      assertThat(schedule.get("creationDate")).as("creationDate must always be set").isNotNull();
      assertThat(schedule.containsKey("params")).isTrue();
    }

    // Spot check: the older v3 shape with no separate workflowId field at all - its own _id
    // doubles as the workflow reference (see _0025's javadoc).
    Document legacyShape =
        collection("workflow_schedules").find(Filters.eq("_id", new ObjectId("6144265f1950a72949b00efc"))).first();
    assertThat(legacyShape).isNotNull();
    assertThat(legacyShape.getString("workflowRef")).isEqualTo("6144265f1950a72949b00efc");
    assertThat(legacyShape.getString("cronSchedule"))
        .isEqualTo("0 0 * ? * MON,TUE,WED,THU,FRI,SAT,SUN");
    assertThat(legacyShape.getString("timezone")).isEqualTo("Australia/Melbourne");
    assertThat(legacyShape.getString("type")).isEqualTo("advancedCron");
    assertThat(legacyShape.getString("status")).isEqualTo("active");

    // Spot check: the newer v3 shape with a real workflowId + parameters[] - proves FIX 2
    // (type hardcoded to "string" rather than always-null).
    Document newerShape =
        collection("workflow_schedules").find(Filters.eq("_id", new ObjectId("61e8fe4897cae7264ceaca76"))).first();
    assertThat(newerShape).isNotNull();
    assertThat(newerShape.getString("workflowRef")).isEqualTo("61637ae9a1747c3cd93f5021");
    assertThat(newerShape.getString("name")).isEqualTo("Run Scheduled Workflow 1");
    assertThat(newerShape.getString("type")).isEqualTo("runOnce");
    assertThat(newerShape.getString("status")).isEqualTo("deleted");
    List<Document> scheduleParams = (List<Document>) newerShape.get("params");
    assertThat(scheduleParams).hasSize(4);
    for (Document param : scheduleParams) {
      assertThat(param.getString("type")).isEqualTo("string");
    }
    assertThat(scheduleParams).anySatisfy(p -> {
      assertThat(p.getString("name")).isEqualTo("timezone");
      assertThat(p.getString("value")).isEqualTo("Australia/Melbourne");
    });
  }

  // =====================================================================================
  // _0029__V3BuildRelationshipGraph invariants (Batch E)
  // =====================================================================================

  private void assertRelationshipGraphBuilt() {
    // ---- THE ONE IRREVERSIBLE RULE: every node id / edge endpoint uses the v5 "workspace:"
    // prefix, never v4's "team:" - _0012__WorkspaceRename runs BEFORE this unit and would never
    // see (and therefore never fix) a "team:" write. ----
    List<String> validNodePrefixes =
        List.of(
            "root:", "workspace:", "user:", "workflow:", "workflowrun:", "task:", "teamtask:",
            "approvergroup:", "schedule:", "integration:");
    for (Document node : collection("rel_nodes").find()) {
      String id = node.getString("_id");
      assertThat(id).as("rel_nodes _id must never carry the v4 team: prefix").doesNotStartWith("team:");
      assertThat(validNodePrefixes.stream().anyMatch(id::startsWith))
          .as("rel_nodes _id %s must start with a v5 type prefix", id)
          .isTrue();
      assertThat(node.getString("type")).as("node type must never be the legacy 'team'").isNotEqualTo("team");
      assertThat(node.containsKey("_class")).as("no rel_nodes document carries _class").isFalse();
    }
    for (Document edge : collection("rel_edges").find()) {
      assertThat(edge.getString("from")).as("rel_edges.from must never carry team:").doesNotStartWith("team:");
      assertThat(edge.getString("to")).as("rel_edges.to must never carry team:").doesNotStartWith("team:");
      assertThat(edge.containsKey("_class")).as("no rel_edges document carries _class").isFalse();
    }

    // ---- Task graph: every one of the 89 migrated tasks is global/root-scoped - v3 has no
    // team-scoped tasks, so zero "teamtask" nodes are ever written for v3 data. ----
    assertThat(collection("tasks").countDocuments()).isEqualTo(89);
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "task"))).isEqualTo(89);
    assertThat(
            collection("rel_edges")
                .countDocuments(Filters.and(Filters.eq("from", "root:root"), Filters.eq("label", "hasTask"))))
        .isEqualTo(89);
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "teamtask")))
        .as("v3 task_templates carries no team-scoping field at all")
        .isZero();

    // ---- Workspace graph: one node + one root--contains--> edge per teams document (86: 28 real
    // v3 teams + 1 system + 57 personal). ----
    assertThat(collection("teams").countDocuments()).isEqualTo(86);
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "workspace"))).isEqualTo(86);
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "root:root"),
                        Filters.eq("label", "contains"),
                        Filters.regex("to", "^workspace:"))))
        .isEqualTo(86);

    // ---- User graph: one node (slug = email) + root--contains--> edge per user (57). ----
    assertThat(collection("users").countDocuments()).isEqualTo(57);
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "user"))).isEqualTo(57);
    Document tysonNode = collection("rel_nodes").find(Filters.eq("_id", "user:614415021950a72949b00efb")).first();
    assertThat(tysonNode).isNotNull();
    assertThat(tysonNode.getString("slug")).isEqualTo("tyson@lawrie.com.au");

    // ---- Personal-workspace memberOf edges: exactly one per user (the batch instructions'
    // explicit ask), resolved via _0028's type=personal/externalRef=<userId> linkage. ----
    assertThat(collection("teams").countDocuments(Filters.eq("type", "personal"))).isEqualTo(57);
    Document tysonPersonal =
        collection("teams")
            .find(Filters.and(Filters.eq("type", "personal"), Filters.eq("externalRef", "614415021950a72949b00efb")))
            .first();
    assertThat(tysonPersonal).isNotNull();
    String tysonPersonalWorkspaceId = tysonPersonal.get("_id").toString();
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "user:614415021950a72949b00efb"),
                        Filters.eq("label", "memberOf"),
                        Filters.eq("to", "workspace:" + tysonPersonalWorkspaceId)))
                .first())
        .as("every user must be a member of their own personal workspace")
        .isNotNull();

    // ---- Real v3 team membership edges - the flowTeams data-loss finding: _0028 was amended to
    // stash it as flowTeamRefs (see that unit's javadoc) specifically so this unit could rebuild
    // these edges; verified against the real dump: 27 total flowTeams references across 24 real
    // users, all 27 resolving to a migrated workspace. ----
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "user:614415021950a72949b00efb"),
                        Filters.eq("label", "memberOf"),
                        Filters.eq("to", "workspace:61551ffaa1747c3cd93f4bed")))
                .first())
        .as("Tyson must be a member of Team Tyson via real v3 flowTeams, not just his personal workspace")
        .isNotNull();
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "user:614415021950a72949b00efb"),
                        Filters.eq("label", "memberOf"),
                        Filters.eq("to", "workspace:615428b57162702bd3ee7605")))
                .first())
        .as("Tyson must be a member of SRC Innovations via real v3 flowTeams")
        .isNotNull();
    long hobbyMemberships = 0;
    for (Document edge : collection("rel_edges").find(Filters.eq("label", "memberOf"))) {
      String to = edge.getString("to");
      Document workspace = collection("teams").find(Filters.eq("_id", new ObjectId(to.substring("workspace:".length())))).first();
      if (workspace != null && "hobby".equals(workspace.getString("type"))) {
        hobbyMemberships++;
      }
    }
    assertThat(hobbyMemberships).as("27 real v3 flowTeams memberships, all resolved to a workspace").isEqualTo(27);

    // ---- Workflow ownership edges: all 65 workflows resolve (system/team/user scope), via
    // _0023's preserved scope/ownerRef extra fields - confirmed NOT a data-loss bug. ----
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "workflow"))).isEqualTo(65);
    assertThat(collection("rel_edges").countDocuments(Filters.eq("label", "hasWorkflow"))).isEqualTo(65);

    Document system = collection("teams").find(Filters.eq("name", "system")).first();
    String systemWorkspaceId = system.get("_id").toString();
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "workspace:" + systemWorkspaceId),
                        Filters.eq("label", "hasWorkflow"),
                        Filters.eq("to", "workflow:6144265f1950a72949b00efc")))
                .first())
        .as("scope=system workflow (Better Uptime Heartbeat) must attach to the system workspace")
        .isNotNull();
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "workspace:61551ffaa1747c3cd93f4bed"),
                        Filters.eq("label", "hasWorkflow"),
                        Filters.eq("to", "workflow:6437a414ec19f9693daab0c2")))
                .first())
        .as("scope=team workflow (Approval Generator) must attach directly to its owning team's workspace"
            + " (ownerRef IS the workspace id)")
        .isNotNull();
    assertThat(
            collection("rel_edges")
                .find(
                    Filters.and(
                        Filters.eq("from", "workspace:" + tysonPersonalWorkspaceId),
                        Filters.eq("label", "hasWorkflow"),
                        Filters.eq("to", "workflow:614a5b92d1577f36a529507c")))
                .first())
        .as("scope=user workflow (Personal Storage Test, owned by Tyson) must attach to the owning user's"
            + " PERSONAL workspace - v5 has no direct user-workflow edge")
        .isNotNull();

    // ---- WorkflowRun ownership edges: all 18093 resolve directly from _0025's own preserved
    // scope/ownerRef extra fields (no join back through the workflow needed). Batched. ----
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "workflowrun"))).isEqualTo(18093);
    assertThat(collection("rel_edges").countDocuments(Filters.eq("label", "hasWorkflowRun"))).isEqualTo(18093);
    Document sampleRunEdge =
        collection("rel_edges")
            .find(
                Filters.and(
                    Filters.eq("label", "hasWorkflowRun"), Filters.eq("to", "workflowrun:614427071950a72949b00eff")))
            .first();
    assertThat(sampleRunEdge).as("scope=system run must resolve to a workspace").isNotNull();
    assertThat(sampleRunEdge.getString("from")).isEqualTo("workspace:" + systemWorkspaceId);

    // ---- Deliberately NOT built - see _0029's javadoc "What this unit deliberately does NOT
    // create" section: no live app code writes a schedule/integration relationship node, and no
    // v3->v5 integration migration exists at all. ----
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "schedule"))).isZero();
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "integration"))).isZero();
  }

  // =====================================================================================
  // _0030__V3SystemWorkspaceMembers invariants (Batch E)
  // =====================================================================================

  private void assertSystemWorkspaceMembersAttached() {
    Document system = collection("teams").find(Filters.eq("name", "system")).first();
    assertThat(system).isNotNull();
    String workspaceNodeId = "workspace:" + system.get("_id").toString();

    assertThat(collection("users").countDocuments(Filters.eq("type", "admin"))).isEqualTo(4);
    long adminMemberships = 0;
    for (Document admin : collection("users").find(Filters.eq("type", "admin"))) {
      String userNodeId = "user:" + admin.get("_id").toString();
      Document edge =
          collection("rel_edges")
              .find(Filters.and(Filters.eq("from", userNodeId), Filters.eq("label", "memberOf"), Filters.eq("to", workspaceNodeId)))
              .first();
      assertThat(edge).as("admin %s must be a system workspace member", admin.getString("email")).isNotNull();
      assertThat(((Document) edge.get("data")).getString("role"))
          .as("system workspace admin membership must carry the owner role")
          .isEqualTo("owner");
      adminMemberships++;
    }
    assertThat(adminMemberships)
        .as("all 4 real v3 admin users must be attached once _0029 created their user nodes"
            + " (the exact gap _0014 could not close on a v3 install - see its own '0 admins ... 0"
            + " skipped' log)")
        .isEqualTo(4);
  }

  // =====================================================================================
  // _0032__V3SeedAudit invariants (Batch E)
  // =====================================================================================

  private void assertAuditSeeded() {
    assertThat(collection("audit").countDocuments(Filters.eq("scope", "TEAM"))).isEqualTo(86);
    assertThat(collection("audit").countDocuments(Filters.eq("scope", "WORKFLOW"))).isEqualTo(65);

    Document system = collection("teams").find(Filters.eq("name", "system")).first();
    String systemWorkspaceId = system.get("_id").toString();
    Document systemAudit =
        collection("audit").find(Filters.and(Filters.eq("scope", "TEAM"), Filters.eq("selfRef", systemWorkspaceId))).first();
    assertThat(systemAudit).isNotNull();
    assertThat(systemAudit.getString("selfName")).isEqualTo("system");
    assertThat(((Document) systemAudit.get("data")).getString("name")).isEqualTo("system");
    assertThat(systemAudit.containsKey("parent")).as("workspace audit records have no parent").isFalse();

    // legacy 4038's workflow half never worked (matched an uppercase node type that is never
    // written) - this is the fix: a real workflow audit record, parent resolved via rel_edges.
    Document heartbeatAudit =
        collection("audit")
            .find(Filters.and(Filters.eq("scope", "WORKFLOW"), Filters.eq("selfRef", "6144265f1950a72949b00efc")))
            .first();
    assertThat(heartbeatAudit).isNotNull();
    assertThat(heartbeatAudit.getString("selfName")).isEqualTo("better-uptime-heartbeat");
    assertThat(heartbeatAudit.getString("parent"))
        .as("a workflow audit record's parent is the WORKSPACE'S OWN AUDIT RECORD id, not the workspace's domain _id")
        .isEqualTo(systemAudit.get("_id").toString());
  }

  private Map<String, Long> snapshotCounts() {
    Map<String, Long> counts = new TreeMap<>();
    for (String name : db.listCollectionNames()) {
      counts.put(name, db.getCollection(name).countDocuments());
    }
    return counts;
  }

  private static MongoCollection<Document> collection(String bareName) {
    return db.getCollection(prefixed(bareName));
  }

  private static String prefixed(String bareName) {
    return COLLECTION_PREFIX + "_" + bareName;
  }
}
