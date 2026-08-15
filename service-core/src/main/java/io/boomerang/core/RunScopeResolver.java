package io.boomerang.core;

import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import java.util.List;
import java.util.Optional;

/**
 * The seam through which workspace scope enters the run/request submission and query paths (J1,
 * H7 - {@code specifications/merge-execution-plan.md}, {@code
 * specifications/consolidation-proposal.md} §5). Introduced so the api {@code
 * Workspace*Service} shims stop reaching into {@link RelationshipService} directly for scope
 * resolution/authorization-adjacent decisions, and so the same workspace-scoped v2 surface can
 * serve {@code flow.mode=engine} - where there is no {@code workspace} module, no membership
 * graph, and the caller-supplied {@code {team}}/{@code {workspace}} path segment is meaningless -
 * by remapping every scope to the single implicit {@code "default"} workspace.
 *
 * <p>Two implementations, chosen by {@link io.boomerang.config.ConditionalOnFlowMode}:
 *
 * <ul>
 *   <li>{@link StandaloneRunScopeResolver} - delegates unchanged to the {@link
 *       RelationshipService} filter/check/createNodeAndEdge/getParentByLabel calls the shims made
 *       inline before this seam existed. Behaviour is byte-for-byte identical.
 *   <li>{@link EngineRunScopeResolver} - constant single-anchor: every {@code team}/{@code
 *       workspace} value resolves to {@code "default"}, every authorization-shaped check passes,
 *       and every write lands under that one anchor - see its javadoc for why lookups still go
 *       through the real relationship graph rather than short-circuiting entirely.
 * </ul>
 *
 * <p>Only calls that are about run/request scoping route through here. Genuinely
 * workspace-domain concerns (member CRUD, quotas, insights) stay on {@code
 * io.boomerang.workspace.WorkspaceService} and remain standalone-only - this seam does not
 * attempt to replace that surface (see the shim call sites still holding an {@code
 * ObjectProvider<WorkspaceService>}/{@code ObjectProvider<ScheduleService>} for those).
 */
public interface RunScopeResolver {

  /**
   * Resolve the caller-supplied {@code {team}}/{@code {workspace}} path segment to the scope
   * value this request should use everywhere downstream - relationship anchoring, param layers,
   * execution annotations. STANDALONE returns it unchanged ({@link RelationshipService} resolves
   * ref-or-slug itself on the calls below). ENGINE ignores the value and always answers {@code
   * "default"}.
   */
  String resolve(String team);

  /**
   * Filter {@code toRefsOrSlugs} of {@code toType} down to those owned by the {@code team}
   * workspace scope - i.e. {@link RelationshipService#filter(RelationshipType, Optional,
   * Optional, Optional, Boolean)} anchored on {@code WORKSPACE:team}. ENGINE anchors on the
   * single {@code workspace:default} node instead of a principal's membership walk, so results
   * stay real (every node ever linked via {@link #linkToScope}), never a raw passthrough.
   */
  List<String> filterInScope(
      RelationshipType toType,
      Optional<List<String>> toRefsOrSlugs,
      String team,
      boolean returnSlugs);

  /**
   * Whether {@code ref} of {@code toType} is owned by the {@code team} workspace scope - i.e.
   * {@link RelationshipService#check(RelationshipType, String, Optional, Optional)} anchored on
   * {@code WORKSPACE:team}. Callers use this both as an access gate and, on create paths, as a
   * duplicate-name guard - so this stays a real existence check in every mode: ENGINE answers it
   * against the single {@code workspace:default} anchor rather than a per-principal walk, but the
   * answer still reflects reality (no blanket {@code true}).
   */
  boolean checkInScope(RelationshipType toType, String ref, String team);

  /**
   * Whether the current principal is a member of the {@code team} workspace itself, rather than
   * of a specific resource owned by it - i.e. {@link RelationshipService#check(RelationshipType,
   * String, Optional, Optional)} against {@link RelationshipType#WORKSPACE} directly. ENGINE
   * always answers {@code true}.
   */
  boolean checkMembership(String team);

  /**
   * Record that a newly-created resource of {@code toType} belongs to the {@code team} workspace
   * scope - i.e. {@link RelationshipService#createNodeAndEdge}. STANDALONE behaviour is
   * unchanged (the workspace node is expected to already exist). ENGINE lazily ensures the
   * single {@code workspace:default} anchor node exists first, since there is no {@code
   * WorkspaceService} to have created it.
   */
  void linkToScope(
      String team, RelationshipLabel label, RelationshipType toType, String toRef, String toSlug);

  /**
   * The owning workspace scope of an existing {@code type:ref} node, found via its incoming
   * {@code label} edge - i.e. {@link RelationshipService#getParentByLabel}. Used by event
   * ingress (webhook/CloudEvent triggers) to recover which workspace a workflow belongs to.
   * ENGINE always answers {@code "default"} without a graph lookup - every resource belongs to
   * it.
   */
  String parentScope(RelationshipLabel label, RelationshipType type, String ref);
}
