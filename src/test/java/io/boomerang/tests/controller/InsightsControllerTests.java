package io.boomerang.tests.controller;

import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.mockito.Mock;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.boomerang.controller.InsightsController;
import io.boomerang.misc.FlowTests;
import io.boomerang.model.InsightsSummary;
import io.boomerang.mongo.entity.FlowUserEntity;
import io.boomerang.mongo.model.TokenScope;
import io.boomerang.mongo.model.UserType;
import io.boomerang.service.UserIdentityService;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
class InsightsControllerTests extends FlowTests {

  @Autowired
  private InsightsController insightsController;

  @Mock
  private UserIdentityService service;

  @Test
  void testGetInsightsTeamAndWorkflowFiltered() {

    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(TokenScope.user);
    when(service.getCurrentUser()).thenReturn(user);

    List<String> workflowIds = new ArrayList<>();
    workflowIds.add("5d1a188af6ca2c00014c4314");

    List<String> teamIds = new ArrayList<>();
    teamIds.add("5d1a1841f6ca2c00014c4309");

    Optional<Direction> order = getOptionalOrder(Direction.ASC);
    Optional<List<String>> scopes = Optional.empty();
    Optional<String> sort = getOptionalString("sort");
    Optional<List<String>> workflowIdsList = getOptionalListString(workflowIds);
    Optional<List<String>> teamIdsList = getOptionalListString(teamIds);
    InsightsSummary summary =
        insightsController.getInsights(order, scopes, sort, workflowIdsList, teamIdsList, 0,
            2147483647, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Assertions.assertEquals(5, summary.getExecutions().size());
    Long executiontime =
        (summary.getExecutions().get(0).getDuration() + summary.getExecutions().get(1).getDuration()
            + summary.getExecutions().get(2).getDuration()
            + summary.getExecutions().get(3).getDuration()
            + summary.getExecutions().get(4).getDuration()) / summary.getExecutions().size();
    Assertions.assertEquals(executiontime, summary.getMedianExecutionTime());
    Assertions.assertEquals(5, summary.getTotalActivitiesExecuted().intValue());
  }

  @Test
  void testGetInsightsTeamFiltered() {
    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(TokenScope.user);
    when(service.getCurrentUser()).thenReturn(user);


    List<String> teamIds = new ArrayList<>();
    teamIds.add("5d1a1841f6ca2c00014c4309");
    Optional<List<String>> scopes = Optional.empty();
    InsightsSummary summary = insightsController.getInsights(getOptionalOrder(Direction.ASC),
        scopes, getOptionalString("sort"), Optional.empty(), getOptionalListString(teamIds), 0,
        2147483647, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Assertions.assertEquals(6, summary.getExecutions().size());
    Long executiontime =
        (summary.getExecutions().get(0).getDuration() + summary.getExecutions().get(1).getDuration()
            + summary.getExecutions().get(2).getDuration()
            + summary.getExecutions().get(3).getDuration()
            + summary.getExecutions().get(4).getDuration()
            + summary.getExecutions().get(5).getDuration()) / summary.getExecutions().size();
    Assertions.assertEquals(executiontime, summary.getMedianExecutionTime());
    Assertions.assertEquals(6, summary.getTotalActivitiesExecuted().intValue());
  }


  @Test
  void testGetInsights() {
    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(TokenScope.user);
    when(service.getCurrentUser()).thenReturn(user);

    InsightsSummary summary = insightsController.getInsights(getOptionalOrder(Direction.ASC),
        Optional.empty(), getOptionalString("sort"), Optional.empty(), Optional.empty(), 0,
        2147483647, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Assertions.assertEquals(6, summary.getExecutions().size());
    Long executiontime =
        (summary.getExecutions().get(0).getDuration() + summary.getExecutions().get(1).getDuration()
            + summary.getExecutions().get(2).getDuration()
            + summary.getExecutions().get(3).getDuration()
            + summary.getExecutions().get(4).getDuration()
            + summary.getExecutions().get(5).getDuration()) / summary.getExecutions().size();
    Assertions.assertEquals(executiontime, summary.getMedianExecutionTime());
    Assertions.assertEquals(6, summary.getTotalActivitiesExecuted().intValue());

  }

  Optional<String> getOptionalString(String string) {
    return Optional.of(string);
  }

  Optional<List<String>> getOptionalListString(List<String> strings) {
    return Optional.of(strings);
  }

  Optional<Direction> getOptionalOrder(Direction direction) {
    return Optional.of(direction);
  }
}
