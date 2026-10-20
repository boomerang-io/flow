package io.boomerang.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code GET /api/v2/workspace/{workspace}/schedule/query} for a workspace with no workflows: a
 * workspace the caller can reach lists an empty page, one it cannot reach is a 404.
 *
 * <p>The identity is a {@code session}-scope member, not the base class's global token: a global
 * token passes every relationship check, so it cannot tell the two cases apart.
 */
class ScheduleWorkspaceQueryTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "schedule-query-member";
  private static final String EMPTY_WORKSPACE = "schedule-query-empty-ws";
  private static final String FOREIGN_WORKSPACE = "schedule-query-foreign-ws";

  @Autowired private ScheduleService scheduleService;

  @BeforeEach
  void seedTwoWorkspacesAndAMemberIdentity() {
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    for (String workspace : List.of(EMPTY_WORKSPACE, FOREIGN_WORKSPACE)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspace,
          workspace,
          Optional.empty(),
          Optional.empty());
    }
    relationshipService.createEdge(
        RelationshipType.USER,
        MEMBER,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        EMPTY_WORKSPACE,
        Optional.empty());

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    principal.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, EMPTY_WORKSPACE, List.of("**/read"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void aReachableWorkspaceWithNoWorkflowsAnswersAnEmptyPageNotAnError() {
    Page<WorkflowSchedule> page = query(EMPTY_WORKSPACE);
    assertEquals(0, page.getTotalElements());
    assertTrue(page.getContent().isEmpty());
    assertEquals(50, page.getPageable().getPageSize(), "the requested page shape is preserved");
  }

  @Test
  void anUnreachableWorkspaceIs404() {
    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> query(FOREIGN_WORKSPACE));
    assertEquals(BoomerangError.WORKSPACE_INVALID_REF.getReason(), ex.getReason());
  }

  private Page<WorkflowSchedule> query(String workspace) {
    return scheduleService.query(
        workspace,
        0,
        50,
        Sort.by("creationDate"),
        Optional.of(List.of("active")),
        Optional.empty(),
        Optional.empty());
  }
}
