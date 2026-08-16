package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.WriteModel;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Builds the {@code rel_nodes}/{@code rel_edges} relationship graph for the v3-migrated
 * data Batches B/C/D already wrote in v5 shape ({@code tasks}, {@code teams} (=workspaces), {@code
 * users}, {@code workflows}, {@code workflow_runs}) - the piece a v3 install has never had, since
 * the relationship model does not exist in v3 at all.
 *
 * <p>This SQUASHES legacy changesets {@code 4002} (workflow/run {@code belongs-to} relationships),
 * {@code 4004}'s graph half ({@code root--hasTask-->task}), {@code 4005}'s graph half, {@code
 * 4011}'s graph half, {@code 4014}'s graph half ({@code user--memberOf-->personal-workspace}),
 * {@code 4015}'s graph half ({@code root--contains-->workspace} for real teams), {@code 4024}, and
 * {@code 4031}/{@code 4041} (the relationship-model introduction itself, retargeted to write {@code
 * workspace:<ref>} DIRECTLY per the ONE IRREVERSIBLE RULE - {@code _0012__WorkspaceRename} runs
 * BEFORE this unit in Flamingock order and would never see a {@code team:}-prefixed write again).
 *
 * <p><b>Graph shape, verified against how the LIVE application code actually writes this graph
 * today</b> (not the legacy intermediate collections, which are dead per the batch table -
 * {@code UserService.getAndRegisterUser}, {@code WorkspaceService.create}/{@code
 * addMembers}, {@code WorkspaceWorkflowService.create}, {@code RelationshipEventListener}, {@code
 * WorkspaceTaskService.create}):
 *
 * <ul>
 *   <li>{@code root:root --hasTask--> task:<id>} for EVERY row in {@code tasks} - v3's {@code
 *       task_templates} carries no team-scoping field at all (verified against the real dump and
 *       {@code _0022}'s own javadoc/code - no {@code flowTeamId}/{@code scope} read anywhere), so
 *       there are zero {@code teamtask} nodes to write for v3 data; every migrated task is global,
 *       matching {@code _0017__SeedTaskCatalogue}'s seeded-catalogue shape exactly (this unit is
 *       that seed's v3 counterpart - {@code _0017} skips entirely on a v3 install specifically so
 *       this unit can do it once {@code task_templates} has been folded into {@code tasks}).
 *   <li>{@code root:root --contains--> workspace:<id>} for EVERY row in {@code teams} (real v3
 *       teams, the seeded {@code system} workspace, and Batch C's per-user personal workspaces
 *       alike) - {@code WorkspaceService.create} writes exactly this edge for every workspace type;
 *       insert-if-absent naturally no-ops on the {@code system} workspace's edge, already seeded by
 *       {@code _0014}.
 *   <li>{@code root:root --contains--> user:<id>} for EVERY row in {@code users}, slug = email -
 *       matches {@code UserService.getAndRegisterUser}'s node write exactly.
 *   <li>{@code user:<id> --memberOf--> workspace:<personalWorkspaceId>} for every user, resolved via
 *       Batch C's {@code teams} where {@code type=personal, externalRef=<userId>} linkage (per the
 *       batch instructions).
 *   <li><b>{@code user:<id> --memberOf--> workspace:<teamId>}</b> for every id in the user's {@code
 *       flowTeamRefs} (a NEW extra field {@code _0028} was amended to stash - see that unit's
 *       amended javadoc: {@code users.flowTeams} is v3's ONLY source of team membership, TeamEntity
 *       has no embedded {@code users[]} counterpart, and it would otherwise be silently and
 *       irrecoverably dropped - the same class of data-loss bug the batch instructions warn about
 *       for workflow ownership, just not the one they named). Ids that do not resolve to a migrated
 *       workspace (stale/deleted team refs) are skipped, logged, not fatal.
 *   <li>{@code workspace:<ownerWorkspaceId> --hasWorkflow--> workflow:<id>}, slug = the workflow's
 *       v5 {@code name} - matches {@code WorkspaceWorkflowService.create}. The owning workspace is
 *       resolved from {@code _0023}'s preserved {@code scope}/{@code ownerRef} extra fields (see
 *       "Ownership resolution" below) - <b>confirmed NOT lost</b>: {@code _0023} already stashes
 *       both, so no amendment to that unit was needed (unlike {@code _0028}/{@code flowTeams}
 *       above).
 *   <li>{@code workspace:<ownerWorkspaceId> --hasWorkflowRun--> workflowrun:<id>}, slug = the run's
 *       own id - matches {@code RelationshipEventListener.onChildWorkflowRunCreated}. Resolved the
 *       SAME way as workflows, from {@code _0025}'s own preserved {@code scope}/{@code ownerRef}
 *       extra fields on {@code workflow_runs} directly (not via a join back through the workflow) -
 *       {@code _0025}'s javadoc documents this was captured specifically for this unit. Batched
 *       (18093 real runs): existing node/edge ids are pre-fetched into in-memory sets once, then
 *       only the missing ones are written via unordered {@code bulkWrite} in chunks of {@link
 *       #BATCH_SIZE}.
 * </ul>
 *
 * <p><b>Ownership resolution</b> (shared by workflow and workflow-run edges - {@link
 * #resolveOwnerWorkspaceId}): {@code scope=system} -\> the seeded {@code system} workspace;
 * {@code scope=team} -\> {@code ownerRef} IS the workspace id directly (v3 {@code flowTeamId},
 * preserved verbatim by {@code _0023}/{@code _0025}); {@code scope=user} -\> the OWNING USER's
 * personal workspace (v5 has no user-owned-workflow concept - a workflow always attaches to a
 * workspace, never directly to a user, so a v3 {@code scope=user} workflow attaches to that user's
 * personal workspace, matching ruling M-1's intent). Verified against the real dump: of the 65
 * real workflows, 53 are {@code scope=user} (the dominant case, not an edge case), 10 {@code
 * scope=system}, 2 {@code scope=team}; {@code scope=template} workflows never reach this unit at
 * all - {@code _0024} already extracted and deleted them from {@code workflows} by this point.
 *
 * <p><b>What this unit deliberately does NOT create</b> (an in-scope judgement call, not an
 * oversight): {@code schedule}/{@code integration} relationship nodes. Verified against the LIVE
 * application code: {@code ScheduleService}/{@code ScheduleWatcher} never write a {@code
 * schedule:<id>} node or read one back - {@code WorkflowScheduleEntity.workflowRef} is the only
 * link, and team ownership is resolved by walking the WORKFLOW's own {@code hasWorkflow} edge
 * ({@code ScheduleWatcher.resolveTeam}'s own comment: "a denormalized copy could go stale - the
 * graph is always current"). {@link io.boomerang.core.enums.RelationshipLabel} has no {@code
 * hasSchedule} label at all. Writing an orphaned {@code schedule:<id>} node with no edge pointing
 * at it (there is no label for one) would add graph weight nothing ever reads. Integrations are
 * out of scope for a different reason: no v3->v5 integration migration exists anywhere in this
 * program (no v3 dump collection is ever read into {@code integrations}), so there is no v3 data to
 * build a node for.
 *
 * <p>Idempotent throughout: every node and edge write goes through {@link
 * SeedResources#insertIfAbsent} (small collections) or an equivalent pre-fetched-existing-ids diff
 * before an unordered {@code bulkWrite} (the {@code workflow_runs} batch) - a second full run
 * inserts nothing new anywhere in this unit.
 */
@Change(id = "0029-v3-build-relationship-graph", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0029__V3BuildRelationshipGraph {

  private static final Logger LOG = LoggerFactory.getLogger(_0029__V3BuildRelationshipGraph.class);

  private static final String ROOT_NODE_ID = "root:root";
  private static final int BATCH_SIZE = 1000;

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — the relationship graph is already built (or was never a gap).");
      return;
    }

    long[] taskCounts = buildTaskGraph(db, names);
    WorkspaceGraph workspaces = buildWorkspaceGraph(db, names);
    long[] userCounts = buildUserGraph(db, names);
    long personalMemberships = buildPersonalMembershipEdges(db, names, workspaces);
    long teamMemberships = buildRealTeamMembershipEdges(db, names, workspaces);
    long[] workflowCounts = buildWorkflowOwnershipEdges(db, names, workspaces);
    long[] runCounts = buildWorkflowRunOwnershipEdges(db, names, workspaces);

    LOG.info(
        "v3 relationship graph built — tasks: {} nodes/{} edges, workspaces: {} nodes/{} edges, "
            + "users: {} nodes/{} edges, personal memberOf: {}, real-team memberOf: {}, "
            + "workflows: {} resolved/{} unresolved, workflow_runs: {} resolved/{} unresolved",
        taskCounts[0],
        taskCounts[1],
        workspaces.nodesInserted(),
        workspaces.edgesInserted(),
        userCounts[0],
        userCounts[1],
        personalMemberships,
        teamMemberships,
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
  // root --contains--> workspace:<id>  (every row in teams — real teams, system, personal)
  // =====================================================================================

  /** All workspace ids, the personal-workspace lookup by owning user id, and the system workspace id. */
  private record WorkspaceGraph(
      Set<String> allWorkspaceIds,
      Map<String, String> personalWorkspaceIdByUserId,
      String systemWorkspaceId,
      long nodesInserted,
      long edgesInserted) {}

  private WorkspaceGraph buildWorkspaceGraph(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> teams = db.getCollection(names.resolve("teams"));
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
      LOG.warn("No 'system' workspace found while building the graph — _0014 should have seeded one");
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
  // user --memberOf--> workspace:<personalWorkspaceId>  (per the batch instructions)
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
  // user --memberOf--> workspace:<teamId>  (real v3 team membership — see _0028's flowTeamRefs)
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
    // nodes/edges written here are additive over what earlier batches wrote — not restorable,
    // matching the other forward-only v3-only units in this chain.
  }
}
