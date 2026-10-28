package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceSummary;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The profile's member count is people, not memberships: someone in two of the caller's
 * workspaces counts once, while each workspace's summary keeps its own count.
 */
class WorkspaceMembershipOverviewTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;

  @Test
  void aPersonInTwoWorkspacesIsOneMember() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String first = workspace("overview-a-" + suffix);
    String second = workspace("overview-b-" + suffix);
    member("both-" + suffix, first, second);
    member("one-" + suffix, first);

    WorkspaceService.MembershipOverview overview =
        workspaceService.membershipOverview(Map.of(first, "owner", second, "member"));

    assertThat(overview.distinctMembers()).isEqualTo(2);
    assertThat(overview.summaries())
        .extracting(WorkspaceSummary::getName, s -> s.getInsights().getMembers())
        .containsExactlyInAnyOrder(
            tuple("overview-a-" + suffix, 2L), tuple("overview-b-" + suffix, 1L));
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    // Memberships and summaries are keyed by the workspace's id.
    return workspaceService.create(request).getId();
  }

  private void member(String userId, String... workspaces) {
    relationshipService.createNode(RelationshipType.USER, userId, userId, Optional.empty());
    for (String workspace : workspaces) {
      relationshipService.createEdge(
          RelationshipType.USER, userId, RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, workspace, Optional.empty());
    }
  }
}
