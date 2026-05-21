package io.boomerang.tests.controller;

import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.mockito.Mock;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.boomerang.controller.ActivityController;
import io.boomerang.misc.FlowTests;
import io.boomerang.model.FlowActivity;
import io.boomerang.model.ListActivityResponse;
import io.boomerang.mongo.entity.FlowUserEntity;
import io.boomerang.mongo.model.TaskStatus;
import io.boomerang.mongo.model.TokenScope;
import io.boomerang.mongo.model.UserType;
import io.boomerang.service.UserIdentityService;



@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
class ActivityControllerTests extends FlowTests {

  @Autowired
  private ActivityController activityController;

  @Mock
  private UserIdentityService service;

  @Test
  void testGetFlowActivity() {

    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);
    when(service.getCurrentUser()).thenReturn(user);

    FlowActivity activity =
        activityController.getFlowActivity("5d1a18c8f6ca2c00014c4325").getBody();
    Assertions.assertEquals("5d1a18c8f6ca2c00014c4325", activity.getId());
  }

  @Test
  void testGetTaskLog() throws IOException {

    mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    mockServer.expect(times(1), requestTo(containsString("controller/log/stream")))
        .andExpect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.OK));
    MockHttpServletResponse response = new MockHttpServletResponse();
    ResponseEntity<StreamingResponseBody> streamingResponse = activityController
        .getTaskLog(response, "5d1a18c8f6ca2c00014c4325", "58340aec-4661-4768-aeec-307c1553409e");
    streamingResponse.getBody().writeTo(System.out);
    Assertions.assertEquals(HttpStatus.OK, streamingResponse.getStatusCode());
  }

  @Test
  void testGetFlowActivitiesTeamAndWorkflowFiltered() {

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
    ListActivityResponse response =
        activityController.getFlowActivities(order, scopes, sort, workflowIdsList, teamIdsList, 0,
            2147483647, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Assertions.assertEquals(5, response.getRecords().size());
    Assertions.assertEquals(Integer.valueOf(0), response.getPageable().getNumber());
    Assertions.assertEquals(Integer.valueOf(2147483647), response.getPageable().getSize());
    Assertions.assertEquals(Integer.valueOf(5), response.getPageable().getNumberOfElements());
    Assertions.assertEquals(Long.valueOf(5), response.getPageable().getTotalElements());
    Assertions.assertEquals(Integer.valueOf(1), response.getPageable().getTotalPages());
  }

  @Test
  void testGetFlowActivitiesTeamFiltered() {

    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(TokenScope.user);
    when(service.getCurrentUser()).thenReturn(user);


    List<String> teamIds = new ArrayList<>();
    teamIds.add("5d1a1841f6ca2c00014c4309");
    Optional<List<String>> scopes = Optional.empty();
    ListActivityResponse response =
        activityController.getFlowActivities(getOptionalOrder(Direction.ASC), scopes,
            getOptionalString("sort"), Optional.empty(), getOptionalListString(teamIds), 0,
            2147483647, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Assertions.assertEquals(6, response.getRecords().size());
    Assertions.assertEquals(Integer.valueOf(0), response.getPageable().getNumber());
    Assertions.assertEquals(Integer.valueOf(2147483647), response.getPageable().getSize());
    Assertions.assertEquals(Integer.valueOf(6), response.getPageable().getNumberOfElements());
    Assertions.assertEquals(Long.valueOf(6), response.getPageable().getTotalElements());
    Assertions.assertEquals(Integer.valueOf(1), response.getPageable().getTotalPages());
  }

  @Test
  void testGetActivitySummary() {
    FlowUserEntity user = new FlowUserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(TokenScope.user);
    when(service.getCurrentUser()).thenReturn(user);

    Map<String, Long> activitySummary = activityController.getFlowActivitySummary(Direction.ASC, 0,
        2147483647, null, Optional.empty(), null, Optional.empty(), Optional.empty(), null, null);

    Assertions.assertEquals(6, activitySummary.get("all").longValue());
    Assertions.assertEquals(3, activitySummary.get(TaskStatus.completed.getStatus()).longValue());
    Assertions.assertEquals(3, activitySummary.get(TaskStatus.inProgress.getStatus()).longValue());
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
