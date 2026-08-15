package io.boomerang.core;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * STANDALONE {@link RunScopeResolver}: a behaviour-preserving extraction of the relationship
 * calls the api {@code Workspace*Service} shims made inline before this seam existed. The {@code
 * team} value is the ref/slug {@link RelationshipService} already resolves - this class adds no
 * new logic, it only relocates the call so the shims no longer need to know the {@code
 * RelationshipType.WORKSPACE} intermediate shape.
 */
@Component
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class StandaloneRunScopeResolver implements RunScopeResolver {

  private final RelationshipService relationshipService;

  public StandaloneRunScopeResolver(RelationshipService relationshipService) {
    this.relationshipService = relationshipService;
  }

  @Override
  public String resolve(String team) {
    return team;
  }

  @Override
  public List<String> filterInScope(
      RelationshipType toType,
      Optional<List<String>> toRefsOrSlugs,
      String team,
      boolean returnSlugs) {
    return relationshipService.filter(
        toType,
        toRefsOrSlugs,
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(team)),
        returnSlugs);
  }

  @Override
  public boolean checkInScope(RelationshipType toType, String ref, String team) {
    return relationshipService.check(
        toType, ref, Optional.of(RelationshipType.WORKSPACE), Optional.of(List.of(team)));
  }

  @Override
  public boolean checkMembership(String team) {
    return relationshipService.check(
        RelationshipType.WORKSPACE, team, Optional.empty(), Optional.empty());
  }

  @Override
  public void linkToScope(
      String team, RelationshipLabel label, RelationshipType toType, String toRef, String toSlug) {
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        label,
        toType,
        toRef,
        toSlug,
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public String parentScope(RelationshipLabel label, RelationshipType type, String ref) {
    return relationshipService.getParentByLabel(label, type, ref);
  }
}
