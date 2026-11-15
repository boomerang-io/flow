package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.entity.ApproverGroupEntity;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a workspace removes what hung from it alone: its approver groups, and each integration
 * installation no other workspace still links. An installation another workspace links, as a Slack
 * team several workspaces share, stays with that workspace.
 */
class WorkspaceDeleteLinkedRecordsTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private IntegrationsRepository integrationsRepository;
  @Autowired private ApproverGroupRepository approverGroupRepository;
  @Autowired private RelationshipService relationshipService;

  @Test
  void deletingAWorkspaceRemovesItsApproverGroupsAndTheInstallationsOnlyItLinked() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String deleted = createWorkspace("linked-deleted-" + suffix);
    String kept = createWorkspace("linked-kept-" + suffix);

    IntegrationsEntity onlyHere = installation("github_app", "gh-" + suffix);
    link(deleted, onlyHere);
    IntegrationsEntity shared = installation("slack", "slack-" + suffix);
    link(deleted, shared);
    link(kept, shared);
    ApproverGroupEntity group = approverGroup(deleted, "approvers-" + suffix);

    workspaceService.delete(deleted);

    assertThat(integrationsRepository.findById(onlyHere.getId())).isEmpty();
    assertThat(
            relationshipService.doesSlugOrRefExistForType(
                RelationshipType.INTEGRATION, onlyHere.getId()))
        .isFalse();
    assertThat(integrationsRepository.findById(shared.getId())).isPresent();
    assertThat(
            relationshipService.getParentsByLabel(
                RelationshipLabel.HAS_INTEGRATION, RelationshipType.INTEGRATION, shared.getId()))
        .hasSize(1);
    assertThat(approverGroupRepository.findById(group.getId())).isEmpty();
    assertThat(
            relationshipService.doesSlugOrRefExistForType(
                RelationshipType.APPROVERGROUP, group.getId()))
        .isFalse();
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  // Saved and noded as the install callbacks do; the link is the workspace's HAS_INTEGRATION edge.
  private IntegrationsEntity installation(String type, String ref) {
    IntegrationsEntity entity = new IntegrationsEntity();
    entity.setType(type);
    entity.setRef(ref);
    entity = integrationsRepository.save(entity);
    relationshipService.createNode(
        RelationshipType.INTEGRATION, entity.getId(), "", Optional.empty());
    return entity;
  }

  private void link(String workspace, IntegrationsEntity installation) {
    relationshipService.createEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_INTEGRATION,
        RelationshipType.INTEGRATION,
        installation.getId(),
        Optional.empty());
  }

  private ApproverGroupEntity approverGroup(String workspace, String name) {
    ApproverGroupEntity group = new ApproverGroupEntity();
    group.setName(name);
    group.setApprovers(List.of());
    group = approverGroupRepository.save(group);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_APPROVER_GROUP,
        RelationshipType.APPROVERGROUP,
        group.getId(),
        group.getName(),
        Optional.empty(),
        Optional.empty());
    return group;
  }
}
