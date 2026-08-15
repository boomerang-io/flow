package io.boomerang.core;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * ENGINE {@link RunScopeResolver}: constant single-anchor. {@code flow.mode=engine} has no
 * {@code workspace} module, no membership graph, and no authorization surface (security defaults
 * off in this mode) - so every caller-supplied {@code team}/{@code workspace} value is ignored
 * and every scope resolves to the single implicit {@link #DEFAULT_SCOPE} workspace;
 * authorization-shaped checks always pass (J1).
 *
 * <p>Ref/slug filtering ({@link #filterInScope}) and existence checks ({@link #checkInScope})
 * still go through the real {@link RelationshipService} graph, anchored at a single {@code
 * workspace:default} node created lazily on first use ({@link #linkToScope}), rather than
 * mimicking a principal's membership walk. Those two are data-integrity operations (does this ref
 * actually belong to the workspace / would creating it collide with an existing one), not
 * authorization - "all checks pass" (J1) applies to {@link #checkMembership}, the one call shaped
 * purely as per-principal authorization, which has no meaning once there is no identity/membership
 * graph to consult. Degrading {@link #filterInScope}/{@link #checkInScope} to a blanket "return
 * everything"/"always true" would silently break duplicate-name prevention and let bogus refs
 * through to the next layer, instead of keeping the same real behaviour standalone has, just
 * anchored on one workspace instead of many.
 */
@Component
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineRunScopeResolver implements RunScopeResolver {

  /** The single workspace every engine-mode scope resolves to (J1). */
  public static final String DEFAULT_SCOPE = "default";

  private final RelationshipService relationshipService;
  private final AtomicBoolean anchorEnsured = new AtomicBoolean(false);

  public EngineRunScopeResolver(RelationshipService relationshipService) {
    this.relationshipService = relationshipService;
  }

  @Override
  public String resolve(String team) {
    return DEFAULT_SCOPE;
  }

  @Override
  public List<String> filterInScope(
      RelationshipType toType,
      Optional<List<String>> toRefsOrSlugs,
      String team,
      boolean returnSlugs) {
    ensureAnchor();
    return relationshipService
        .findNodes(
            RelationshipType.WORKSPACE,
            DEFAULT_SCOPE,
            toType,
            toRefsOrSlugs,
            Optional.empty(),
            Optional.empty())
        .stream()
        .map(returnSlugs ? RelationshipNodeEntity::getSlug : RelationshipNodeEntity::getRef)
        .collect(Collectors.toList());
  }

  @Override
  public boolean checkInScope(RelationshipType toType, String ref, String team) {
    ensureAnchor();
    return relationshipService.hasNodes(
        RelationshipType.WORKSPACE,
        DEFAULT_SCOPE,
        toType,
        Optional.of(List.of(ref)),
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public boolean checkMembership(String team) {
    // No identity/membership graph exists in engine mode (no workspace module, security off by
    // default) - there is nothing to authorize against, so every principal is a member.
    return true;
  }

  @Override
  public void linkToScope(
      String team, RelationshipLabel label, RelationshipType toType, String toRef, String toSlug) {
    ensureAnchor();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        DEFAULT_SCOPE,
        label,
        toType,
        toRef,
        toSlug,
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public String parentScope(RelationshipLabel label, RelationshipType type, String ref) {
    return DEFAULT_SCOPE;
  }

  /**
   * Lazily creates the single {@code workspace:default} relationship node. Idempotent - {@link
   * RelationshipNodeEntity}'s id is deterministic ({@code type:ref}), so a repeat {@code save()}
   * is a harmless upsert; the flag only avoids the redundant write once it is known to exist.
   */
  private void ensureAnchor() {
    if (anchorEnsured.compareAndSet(false, true)) {
      relationshipService.createNode(
          RelationshipType.WORKSPACE, DEFAULT_SCOPE, DEFAULT_SCOPE, Optional.empty());
    }
  }
}
