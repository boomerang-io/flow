package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * V3-only. Builds the {@code rel_nodes}/{@code rel_edges} relationship graph for the v3 data the
 * earlier v3 units already wrote in v5 shape ({@code tasks}, {@code workspaces}, {@code users},
 * {@code workflows}, {@code workflow_runs}, {@code approver_groups}) - the piece a v3 install has
 * never had, since the relationship model does not exist in v3 at all.
 *
 * <p>This SQUASHES legacy changesets {@code 4002} (workflow/run {@code belongs-to} relationships),
 * {@code 4004}'s graph half ({@code root--hasTask-->task}), {@code 4005}'s graph half, {@code
 * 4011}'s graph half, {@code 4014}'s graph half ({@code user--memberOf-->personal-workspace}),
 * {@code 4015}'s graph half ({@code root--contains-->workspace} for real teams), {@code 4024}, and
 * {@code 4031}/{@code 4041} (the relationship-model introduction itself, written with {@code
 * workspace:<ref>} nodes directly - nothing after this unit renames a {@code team:} node, so one
 * must never be written).
 *
 * <p><b>Graph shape, verified against how the LIVE application code actually writes this graph
 * today</b> (not the legacy intermediate collections, which nothing reads - {@code
 * UserService.getAndRegisterUser}, {@code WorkspaceService.create}/{@code addMembers}, {@code
 * WorkspaceWorkflowService.create}, {@code RelationshipEventListener}, {@code
 * WorkspaceTaskService.create}):
 *
 * <ul>
 *   <li>{@code root:root --hasTask--> task:<id>} for EVERY row in {@code tasks} - v3's {@code
 *       task_templates} carries no team-scoping field at all (verified against the real dump and
 *       {@link _0006__V3MigrateTaskCatalogue} - no {@code flowTeamId}/{@code scope} read anywhere),
 *       so there are zero {@code teamtask} nodes to write for v3 data; every migrated task is
 *       global, matching {@link _0017__SeedTaskCatalogue}'s seeded-catalogue shape exactly (that
 *       seed runs later and writes the same node and edge for any task it inserts).
 *   <li>{@code root:root --contains--> workspace:<id>} for EVERY row in {@code workspaces} (real v3
 *       teams, the seeded {@code system} workspace, and {@link _0008__V3MigrateUsers}'s per-user
 *       personal workspaces alike) - {@code WorkspaceService.create} writes exactly this edge for
 *       every workspace type; insert-if-absent naturally no-ops on the {@code system} workspace's
 *       edge, already seeded by {@link _0004__SeedSystemWorkspace}.
 *   <li>{@code root:root --contains--> user:<id>} for EVERY row in {@code users}, slug = email -
 *       matches {@code UserService.getAndRegisterUser}'s node write exactly.
 *   <li>{@code user:<id> --memberOf--> workspace:<personalWorkspaceId>} for every user,
 *       resolved via the {@code workspaces} where {@code type=personal, externalRef=<userId>}
 *       linkage {@link _0008__V3MigrateUsers} writes.
 *   <li><b>{@code user:<id> --memberOf--> workspace:<teamId>}</b> for every id in the user's {@code
 *       flowTeamRefs} (the hand-off field {@link _0008__V3MigrateUsers} keeps: {@code
 *       users.flowTeams} is v3's ONLY source of team membership, TeamEntity has no embedded {@code
 *       users[]} counterpart). Ids that do not resolve to a migrated workspace (stale/deleted team
 *       refs) are skipped, logged, not fatal.
 *   <li>{@code workspace:<ownerWorkspaceId> --hasWorkflow--> workflow:<id>}, slug = the workflow's
 *       v5 {@code name} - matches {@code WorkspaceWorkflowService.create}. The owning workspace is
 *       resolved from {@link _0010__V3MigrateWorkflows}'s {@code scope}/{@code ownerRef} hand-off
 *       fields (see "Ownership resolution" below).
 *   <li>{@code workspace:<ownerWorkspaceId> --hasWorkflowRun--> workflowrun:<id>}, slug = the run's
 *       own id - matches {@code RelationshipEventListener.onChildWorkflowRunCreated}. Resolved the
 *       SAME way as workflows, from {@link _0012__V3MigrateRuns}'s {@code scope}/{@code ownerRef}
 *       hand-off fields on {@code workflow_runs} directly (not via a join back through the
 *       workflow). Batched (18093 real runs): existing node/edge ids are pre-fetched into in-memory
 *       sets once, then only the missing ones are written via unordered {@code bulkWrite} in chunks
 *       of {@link #BATCH_SIZE}.
 *   <li>{@code workspace:<id> --hasApproverGroup--> approvergroup:<id>} for every approver group,
 *       resolved from the {@code workspaceRef} hand-off field {@link _0007__V3MigrateWorkspaces}
 *       writes.
 * </ul>
 *
 * <p><b>Ownership resolution</b> (shared by workflow and workflow-run edges - {@link
 * #resolveOwnerWorkspaceId}): {@code scope=system} -\> the seeded {@code system} workspace;
 * {@code scope=team} -\> {@code ownerRef} IS the workspace id directly (v3 {@code flowTeamId},
 * preserved verbatim by {@link _0010__V3MigrateWorkflows}/{@link _0012__V3MigrateRuns}); {@code
 * scope=user} -\> the OWNING USER's personal workspace (v5 has no user-owned-workflow concept - a
 * workflow always attaches to a workspace, never directly to a user, so a v3 {@code scope=user}
 * workflow attaches to that user's personal workspace). Verified against the real dump: of the 65
 * real workflows, 53 are {@code scope=user} (the dominant case, not an edge case), 10 {@code
 * scope=system}, 2 {@code scope=team}; {@code scope=template} workflows never reach this unit at
 * all - {@link _0011__V3ExtractWorkflowTemplates} already extracted and deleted them from {@code
 * workflows} by this point.
 *
 * <p>Once the graph is built, {@link #clearHandOffFields} removes those hand-off fields ({@code
 * scope}/{@code ownerRef} on workflows and runs, {@code flowTeamRefs} on users, {@code
 * workspaceRef} on approver groups); nothing reads them afterwards.
 *
 * <p><b>What this unit deliberately does NOT create:</b> {@code schedule}/{@code integration}
 * relationship nodes. Verified against the LIVE application code: {@code ScheduleService}/{@code
 * ScheduleWatcher} never write a {@code
 * schedule:<id>} node or read one back - {@code WorkflowScheduleEntity.workflowRef} is the only
 * link, and team ownership is resolved by walking the WORKFLOW's own {@code hasWorkflow} edge
 * ({@code ScheduleWatcher.resolveTeam}'s own comment: "a denormalized copy could go stale - the
 * graph is always current"). {@link io.boomerang.core.enums.RelationshipLabel} has no {@code
 * hasSchedule} label at all. Writing an orphaned {@code schedule:<id>} node with no edge pointing
 * at it (there is no label for one) would add graph weight nothing ever reads. Integrations are
 * out of scope for a different reason: no v3 unit migrates integrations (no v3 dump collection is
 * ever read into {@code integrations}), so there is no v3 data to build a node for.
 *
 * <p>Idempotent throughout: every node and edge write goes through {@link
 * SeedResources#insertIfAbsent} (small collections) or an equivalent pre-fetched-existing-ids diff
 * before an unordered {@code bulkWrite} (the {@code workflow_runs} batch) - a second full run
 * inserts nothing new anywhere in this unit.
 *
 * <p><b>System workspace admins</b> ({@link #attachSystemWorkspaceAdminMembers}). {@link
 * _0004__SeedSystemWorkspace} runs before the v3 units so the {@code system} workspace document
 * exists when {@link #buildWorkflowOwnershipEdges}/{@link #buildWorkflowRunOwnershipEdges} resolve
 * {@code scope=system} ownership; without it every system-scoped workflow and run would lose its
 * graph node and edge. At that point a v3 install's admins have no {@code user:<id>} node yet
 * (this unit creates them, in {@link #buildUserGraph}), so that unit's admin-bootstrap step skips
 * them; this unit repeats the step once the nodes exist.
 */
@Change(id = "0013-v3-build-relationship-graph", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0013__V3BuildRelationshipGraph {

  private static final Logger LOG = LoggerFactory.getLogger(_0013__V3BuildRelationshipGraph.class);

  private static final String ROOT_NODE_ID = "root:root";
  private static final int BATCH_SIZE = 1000;

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — the relationship graph is already built (or was never a gap).");
      return;
    }

    long[] taskCounts = buildTaskGraph(db, names);
    WorkspaceGraph workspaces = buildWorkspaceGraph(db, names);
    long[] userCounts = buildUserGraph(db, names);
    long personalMemberships = buildPersonalMembershipEdges(db, names, workspaces);
    long teamMemberships = buildRealTeamMembershipEdges(db, names, workspaces);
    long adminMembershipsAttached = attachSystemWorkspaceAdminMembers(db, names, workspaces);
    long[] workflowCounts = buildWorkflowOwnershipEdges(db, names, workspaces);
    long[] runCounts = buildWorkflowRunOwnershipEdges(db, names, workspaces);
    long approverGroups = buildApproverGroupEdges(db, names, workspaces);
    clearHandOffFields(db, names);
    LOG.info("v3 approver groups linked to their workspace: {}", approverGroups);

    LOG.info(
        "v3 relationship graph built — tasks: {} nodes/{} edges, workspaces: {} nodes/{} edges, "
            + "users: {} nodes/{} edges, personal memberOf: {}, real-team memberOf: {}, system"
            + " workspace admin memberOf: {}, workflows: {} resolved/{} unresolved, workflow_runs:"
            + " {} resolved/{} unresolved",
        taskCounts[0],
        taskCounts[1],
        workspaces.nodesInserted(),
        workspaces.edgesInserted(),
        userCounts[0],
        userCounts[1],
        personalMemberships,
        teamMemberships,
        adminMembershipsAttached,
        workflowCounts[0],
        workflowCounts[1],
        runCounts[0],
        runCounts[1]);
  }

  // =====================================================================================
  // root --hasTask--> task:<id>  (every row in tasks — v3 has no team-scoped tasks)
  // =====================================================================================

  private long[] buildTaskGraph(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));
    long nodes = 0;
    long edges = 0;
    for (Document task : tasks.find()) {
      String taskId = task.get("_id").toString();
      String nodeId = "task:" + taskId;
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_nodes"),
          Filters.eq("_id", nodeId),
          SeedResources.node("task", taskId, task.getString("name")))) {
        nodes++;
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", ROOT_NODE_ID), Filters.eq("label", "hasTask"), Filters.eq("to", nodeId)),
          SeedResources.edge(ROOT_NODE_ID, "hasTask", nodeId, new Document()))) {
        edges++;
      }
    }
    return new long[] {nodes, edges};
  }

  // =====================================================================================
  // root --contains--> workspace:<id>  (every row in workspaces — real teams, system, personal)
  // =====================================================================================

  /** All workspace ids, the personal-workspace lookup by owning user id, and the system workspace id. */
  private record WorkspaceGraph(
      Set<String> allWorkspaceIds,
      Map<String, String> personalWorkspaceIdByUserId,
      String systemWorkspaceId,
      long nodesInserted,
      long edgesInserted) {}

  private WorkspaceGraph buildWorkspaceGraph(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> teams = db.getCollection(names.resolve("workspaces"));
    Set<String> allWorkspaceIds = new HashSet<>();
    Map<String, String> personalByUser = new HashMap<>();
    String systemWorkspaceId = null;
    long nodes = 0;
    long edges = 0;

    for (Document workspace : teams.find()) {
      String workspaceId = workspace.get("_id").toString();
      allWorkspaceIds.add(workspaceId);
      String type = workspace.getString("type");
      if ("system".equals(type)) {
        systemWorkspaceId = workspaceId;
      } else if ("personal".equals(type)) {
        String userId = workspace.getString("externalRef");
        if (userId != null) {
          personalByUser.put(userId, workspaceId);
        }
      }

      String nodeId = "workspace:" + workspaceId;
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_nodes"),
          Filters.eq("_id", nodeId),
          SeedResources.node("workspace", workspaceId, workspace.getString("name")))) {
        nodes++;
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", ROOT_NODE_ID), Filters.eq("label", "contains"), Filters.eq("to", nodeId)),
          SeedResources.edge(ROOT_NODE_ID, "contains", nodeId, new Document()))) {
        edges++;
      }
    }

    if (systemWorkspaceId == null) {
      LOG.warn(
          "No 'system' workspace found while building the graph — _0004__SeedSystemWorkspace"
              + " should have seeded one");
    }
    return new WorkspaceGraph(allWorkspaceIds, personalByUser, systemWorkspaceId, nodes, edges);
  }

  // =====================================================================================
  // root --contains--> user:<id>
  // =====================================================================================

  private long[] buildUserGraph(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> users = db.getCollection(names.resolve("users"));
    long nodes = 0;
    long edges = 0;
    for (Document user : users.find()) {
      String userId = user.get("_id").toString();
      String nodeId = "user:" + userId;
      if (SeedResources.insertIfAbsent(
          db, names.resolve("rel_nodes"), Filters.eq("_id", nodeId), SeedResources.node("user", userId, user.getString("email")))) {
        nodes++;
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", ROOT_NODE_ID), Filters.eq("label", "contains"), Filters.eq("to", nodeId)),
          SeedResources.edge(ROOT_NODE_ID, "contains", nodeId, new Document()))) {
        edges++;
      }
    }
    return new long[] {nodes, edges};
  }

  // =====================================================================================
  // user --memberOf--> workspace:<personalWorkspaceId>
  // =====================================================================================

  private long buildPersonalMembershipEdges(MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    long inserted = 0;
    for (Map.Entry<String, String> entry : workspaces.personalWorkspaceIdByUserId().entrySet()) {
      String userNodeId = "user:" + entry.getKey();
      String workspaceNodeId = "workspace:" + entry.getValue();
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", userNodeId), Filters.eq("label", "memberOf"), Filters.eq("to", workspaceNodeId)),
          SeedResources.edge(userNodeId, "memberOf", workspaceNodeId, new Document()))) {
        inserted++;
      }
    }
    return inserted;
  }

  // =====================================================================================
  // user --memberOf--> workspace:<teamId>  (real v3 team membership — see the flowTeamRefs
  // _0008__V3MigrateUsers keeps)
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private long buildRealTeamMembershipEdges(MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    MongoCollection<Document> users = db.getCollection(names.resolve("users"));
    long inserted = 0;
    long unresolved = 0;
    for (Document user : users.find()) {
      List<String> flowTeamRefs = (List<String>) user.get("flowTeamRefs");
      if (flowTeamRefs == null || flowTeamRefs.isEmpty()) {
        continue;
      }
      String userNodeId = "user:" + user.get("_id").toString();
      for (String teamId : flowTeamRefs) {
        if (!workspaces.allWorkspaceIds().contains(teamId)) {
          unresolved++;
          continue;
        }
        String workspaceNodeId = "workspace:" + teamId;
        if (SeedResources.insertIfAbsent(
            db,
            names.resolve("rel_edges"),
            Filters.and(Filters.eq("from", userNodeId), Filters.eq("label", "memberOf"), Filters.eq("to", workspaceNodeId)),
            SeedResources.edge(userNodeId, "memberOf", workspaceNodeId, new Document()))) {
          inserted++;
        }
      }
    }
    if (unresolved > 0) {
      LOG.info("{} v3 flowTeamRefs entries did not resolve to a migrated workspace — skipped", unresolved);
    }
    return inserted;
  }

  // =====================================================================================
  // user --memberOf--> workspace:<systemWorkspaceId>, for admin users (see the class javadoc)
  // =====================================================================================

  /**
   * Repeats {@link _0004__SeedSystemWorkspace}'s admin-bootstrap step, now that {@link
   * #buildUserGraph} (earlier in this SAME unit) has created every admin's {@code user:<id>}
   * node — {@link _0004__SeedSystemWorkspace} runs before the v3 units (see the class javadoc)
   * and so finds none on a v3 install. Reproduces its {@code addAdminMembers} step (same edge
   * shape - {@code data.role=owner}, same skip-if-no-node defensiveness, though by this point
   * every admin SHOULD have a node from {@link #buildUserGraph} just above) rather than depending
   * on that private method directly.
   *
   * <p>Idempotent: every edge is insert-if-absent on {@code (from, label, to)} - a second run
   * inserts nothing new.
   */
  private long attachSystemWorkspaceAdminMembers(
      MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    String systemWorkspaceId = workspaces.systemWorkspaceId();
    if (systemWorkspaceId == null) {
      LOG.warn(
          "No 'system' workspace found — _0004__SeedSystemWorkspace should have seeded one;"
              + " skipping admin membership");
      return 0;
    }
    String workspaceNodeId = "workspace:" + systemWorkspaceId;

    List<Document> admins =
        db.getCollection(names.resolve("users")).find(Filters.eq("type", "admin")).into(new ArrayList<>());
    long added = 0;
    long skipped = 0;
    for (Document admin : admins) {
      String userNodeId = "user:" + admin.get("_id").toString();
      try (MongoCursor<Document> node =
          db.getCollection(names.resolve("rel_nodes")).find(Filters.eq("_id", userNodeId)).iterator()) {
        if (!node.hasNext()) {
          skipped++;
          continue;
        }
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", userNodeId), Filters.eq("label", "memberOf"), Filters.eq("to", workspaceNodeId)),
          SeedResources.edge(userNodeId, "memberOf", workspaceNodeId, new Document("role", "owner")))) {
        added++;
      }
    }
    if (skipped > 0) {
      LOG.warn("{} admin(s) still have no user:<id> node — their system-workspace membership was skipped", skipped);
    }
    return added;
  }

  // =====================================================================================
  // Ownership resolution shared by workflows and workflow_runs
  // =====================================================================================

  private String resolveOwnerWorkspaceId(String scope, String ownerRef, WorkspaceGraph workspaces) {
    if (scope == null) {
      return null;
    }
    return switch (scope) {
      case "system" -> workspaces.systemWorkspaceId();
      case "team" -> (ownerRef != null && workspaces.allWorkspaceIds().contains(ownerRef)) ? ownerRef : null;
      case "user" -> ownerRef != null ? workspaces.personalWorkspaceIdByUserId().get(ownerRef) : null;
      default -> null;
    };
  }

  // =====================================================================================
  // workspace --hasWorkflow--> workflow:<id>
  // =====================================================================================

  private long[] buildWorkflowOwnershipEdges(MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    long resolved = 0;
    long unresolved = 0;
    for (Document workflow : workflows.find()) {
      String workflowId = workflow.get("_id").toString();
      String ownerWorkspaceId =
          resolveOwnerWorkspaceId(workflow.getString("scope"), workflow.getString("ownerRef"), workspaces);
      if (ownerWorkspaceId == null) {
        unresolved++;
        LOG.warn(
            "Workflow {} (scope={}, ownerRef={}) did not resolve to a workspace — no hasWorkflow edge written",
            workflowId,
            workflow.getString("scope"),
            workflow.getString("ownerRef"));
        continue;
      }
      String workspaceNodeId = "workspace:" + ownerWorkspaceId;
      String workflowNodeId = "workflow:" + workflowId;
      SeedResources.insertIfAbsent(
          db, names.resolve("rel_nodes"), Filters.eq("_id", workflowNodeId), SeedResources.node("workflow", workflowId, workflow.getString("name")));
      SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", workspaceNodeId), Filters.eq("label", "hasWorkflow"), Filters.eq("to", workflowNodeId)),
          SeedResources.edge(workspaceNodeId, "hasWorkflow", workflowNodeId, new Document()));
      resolved++;
    }
    return new long[] {resolved, unresolved};
  }

  // =====================================================================================
  // workspace --hasApproverGroup--> approvergroup:<id>
  // =====================================================================================

  /** Link each approver group to the workspace it was extracted from, as the service does. */
  private long buildApproverGroupEdges(
      MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    long linked = 0;
    for (Document group : db.getCollection(names.resolve("approver_groups")).find()) {
      String groupId = group.get("_id").toString();
      String workspaceId = group.getString("workspaceRef");
      if (workspaceId == null || !workspaces.allWorkspaceIds().contains(workspaceId)) {
        LOG.warn("Approver group {} names no migrated workspace - no edge written", groupId);
        continue;
      }
      String groupNodeId = "approvergroup:" + groupId;
      String workspaceNodeId = "workspace:" + workspaceId;
      SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_nodes"),
          Filters.eq("_id", groupNodeId),
          SeedResources.node("approvergroup", groupId, group.getString("name")));
      SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(
              Filters.eq("from", workspaceNodeId),
              Filters.eq("label", "hasApproverGroup"),
              Filters.eq("to", groupNodeId)),
          SeedResources.edge(workspaceNodeId, "hasApproverGroup", groupNodeId, new Document()));
      linked++;
    }
    return linked;
  }

  /** The owner hints earlier v3 units left for this graph build; nothing reads them after it. */
  private void clearHandOffFields(MongoDatabase db, CollectionNames names) {
    unset(db, names.resolve("workflows"), "scope", "ownerRef");
    unset(db, names.resolve("workflow_runs"), "scope", "ownerRef");
    unset(db, names.resolve("users"), "flowTeamRefs");
    unset(db, names.resolve("approver_groups"), "workspaceRef");
  }

  private static void unset(MongoDatabase db, String collection, String... fields) {
    db.getCollection(collection)
        .updateMany(
            Filters.or(Arrays.stream(fields).map(Filters::exists).toList()),
            Updates.combine(Arrays.stream(fields).map(Updates::unset).toList()));
  }

  // =====================================================================================
  // workspace --hasWorkflowRun--> workflowrun:<id>  (batched — 18093 real runs)
  // =====================================================================================

  private long[] buildWorkflowRunOwnershipEdges(MongoDatabase db, CollectionNames names, WorkspaceGraph workspaces) {
    MongoCollection<Document> relNodes = db.getCollection(names.resolve("rel_nodes"));
    MongoCollection<Document> relEdges = db.getCollection(names.resolve("rel_edges"));
    MongoCollection<Document> runs = db.getCollection(names.resolve("workflow_runs"));

    Set<String> existingNodeIds = new HashSet<>();
    for (Document node :
        relNodes.find(Filters.eq("type", "workflowrun")).projection(Projections.include("_id"))) {
      existingNodeIds.add(node.getString("_id"));
    }
    Set<String> existingEdgeTargets = new HashSet<>();
    for (Document edge :
        relEdges.find(Filters.eq("label", "hasWorkflowRun")).projection(Projections.include("to"))) {
      existingEdgeTargets.add(edge.getString("to"));
    }

    long resolved = 0;
    long unresolved = 0;
    List<WriteModel<Document>> nodeBatch = new ArrayList<>(BATCH_SIZE);
    List<WriteModel<Document>> edgeBatch = new ArrayList<>(BATCH_SIZE);

    for (Document run : runs.find().batchSize(BATCH_SIZE)) {
      String runId = run.get("_id").toString();
      String ownerWorkspaceId = resolveOwnerWorkspaceId(run.getString("scope"), run.getString("ownerRef"), workspaces);
      if (ownerWorkspaceId == null) {
        unresolved++;
        continue;
      }
      resolved++;
      String runNodeId = "workflowrun:" + runId;
      String workspaceNodeId = "workspace:" + ownerWorkspaceId;

      if (!existingNodeIds.contains(runNodeId)) {
        nodeBatch.add(new InsertOneModel<>(SeedResources.node("workflowrun", runId, runId)));
        existingNodeIds.add(runNodeId);
      }
      if (!existingEdgeTargets.contains(runNodeId)) {
        edgeBatch.add(new InsertOneModel<>(SeedResources.edge(workspaceNodeId, "hasWorkflowRun", runNodeId, new Document())));
        existingEdgeTargets.add(runNodeId);
      }

      if (nodeBatch.size() >= BATCH_SIZE) {
        relNodes.bulkWrite(nodeBatch, new BulkWriteOptions().ordered(false));
        nodeBatch.clear();
      }
      if (edgeBatch.size() >= BATCH_SIZE) {
        relEdges.bulkWrite(edgeBatch, new BulkWriteOptions().ordered(false));
        edgeBatch.clear();
      }
    }
    if (!nodeBatch.isEmpty()) {
      relNodes.bulkWrite(nodeBatch, new BulkWriteOptions().ordered(false));
    }
    if (!edgeBatch.isEmpty()) {
      relEdges.bulkWrite(edgeBatch, new BulkWriteOptions().ordered(false));
    }
    if (unresolved > 0) {
      LOG.warn("{} workflow_runs did not resolve to a workspace — no hasWorkflowRun edge written", unresolved);
    }
    return new long[] {resolved, unresolved};
  }

  @Rollback
  public void rollback() {
    // The graph may already be load-bearing for authz/queries by the time a rollback runs, and
    // nodes/edges written here are additive over what the seeds wrote — not restorable,
    // matching the other forward-only v3-only units in this chain.
  }
}
