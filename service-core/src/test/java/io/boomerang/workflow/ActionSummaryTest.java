package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.security.IdentityService;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.model.ActionSummary;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ActionSummaryTest {

  // Actions in the workspace's one workflow, by type and status.
  private static final Map<String, Long> STORED =
      Map.of(
          "approval/submitted", 4L,
          "approval/approved", 3L,
          "approval/rejected", 1L,
          "manual/submitted", 2L,
          "manual/approved", 5L);

  private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
  private final RelationshipService relationshipService = mock(RelationshipService.class);
  private ActionService actionService;

  @BeforeEach
  void setUp() {
    actionService =
        new ActionService(
            mock(ActionRepository.class),
            mock(ApproverGroupRepository.class),
            mock(TaskRunService.class),
            mock(WorkflowService.class),
            relationshipService,
            mock(UserService.class),
            mock(IdentityService.class),
            mongoTemplate);
    when(relationshipService.filter(
            eq(RelationshipType.WORKFLOW), any(), any(), any(), any(Boolean.class)))
        .thenReturn(List.of("w1"));
    when(mongoTemplate.count(any(Query.class), eq(ActionEntity.class)))
        .thenAnswer(
            invocation -> {
              String query = ((Query) invocation.getArgument(0)).getQueryObject().toString();
              assertThat(query).as("every count is limited to the workspace's workflows").contains("w1");
              long total = 0;
              for (ActionType type : ActionType.values()) {
                for (ActionStatus status : ActionStatus.values()) {
                  if (Pattern.compile("\\b" + type + "\\b").matcher(query).find()
                      && Pattern.compile("\\b" + status + "\\b").matcher(query).find()) {
                    total += STORED.getOrDefault(type + "/" + status, 0L);
                  }
                }
              }
              return total;
            });
  }

  @Test
  void countsWaitingActionsByDefault() {
    ActionSummary summary =
        actionService.summary("cheer", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    assertThat(summary.getApprovals()).isEqualTo(4);
    assertThat(summary.getManual()).isEqualTo(2);
  }

  @Test
  void countsTheStatusesAskedFor() {
    ActionSummary summary =
        actionService.summary(
            "cheer",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of(ActionStatus.approved, ActionStatus.rejected)));

    assertThat(summary.getApprovals()).isEqualTo(4);
    assertThat(summary.getManual()).isEqualTo(5);
  }

  @Test
  void approvalRateIsApprovedOverDecidedApprovals() {
    ActionSummary summary =
        actionService.summary("cheer", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    // 3 approved of 4 decided. It was ((approved + rejected) / total) * 100 in integer arithmetic,
    // so only ever 0 or 100, and counted every workspace.
    assertThat(summary.getApprovalsRate()).isEqualTo(75);
  }
}
