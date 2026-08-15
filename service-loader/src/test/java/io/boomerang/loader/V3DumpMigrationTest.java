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
    // migrated by this slice's own _0021/_0031 - see assertSettingsMigrated/
    // assertGlobalParametersMigrated below, not here.)
    assertThat(collection("workflows").countDocuments()).isEqualTo(workflowsBefore);
    assertThat(collection("users").countDocuments()).isEqualTo(usersBefore);
    assertThat(collection("settings").countDocuments()).isGreaterThan(0);
    assertThat(collection("task_templates").countDocuments()).isGreaterThan(0);
    assertThat(collection("teams").countDocuments()).isGreaterThan(0);
    assertThat(collection("workflows_activity").countDocuments()).isGreaterThan(0);
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

    // _0017__SeedTaskCatalogue: no v5 catalogue seeded over the v3 task_templates data.
    assertThat(collection("tasks").countDocuments()).isZero();
    assertThat(collection("task_revisions").countDocuments()).isZero();

    // _0018__SeedTemplates: no starter templates seeded either (their source workflows still
    // live in the untouched "workflows" collection).
    assertThat(collection("workflow_templates").countDocuments()).isZero();
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
  // Later slices: add a new clearly-marked assertion section here per v3->v5 changeunit
  // (e.g. workspace/team migration, the settings squash, the task-catalogue squash, ...)
  // rather than folding new checks into the sections above.
  // =====================================================================================

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
