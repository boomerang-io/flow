package io.boomerang.misc;

import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import io.boomerang.tests.AbstractFlowTests;


public class FlowTests extends AbstractFlowTests {

  protected MockRestServiceServer mockServer;

  @Autowired
  @Qualifier("internalRestTemplate")
  protected RestTemplate restTemplate;

  @Override
  protected String[] getCollections() {
    return new String[] {"core_access_tokens", "core_groups_higher_level",
        "core_groups_lower_level", "core_tool_templates", "core_tools", "core_users",
        "core_settings", "core_audit", "requests_creategroup", "requests_createtool",
        "requests_removegroup", "requests_leavetool", "flow_teams", "flow_workflows",
        "flow_workflows_activity", "flow_workflows_activity_task", "flow_workflows_revisions",
        "flow_task_templates", "flow_settings"};
  }

  @Override
  protected Map<String, List<String>> getData() {
    LinkedHashMap<String, List<String>> data = new LinkedHashMap<>();
    data.put("core_users", Arrays.asList("db/core_users/user1.json", "db/core_users/user2.json",
        "db/core_users/user3.json", "db/core_users/user4.json"));

    data.put("core_groups_higher_level",
        Arrays.asList("db/core_groups_higher_level/highlevelgroup.json",
            "db/core_groups_higher_level/highlevelgroup2.json",
            "db/core_groups_higher_level/highlevelgroup3.json"));
    data.put("core_groups_lower_level",
        Arrays.asList("db/core_groups_lower_level/lowerlevelgroup.json"));

    data.put("flow_workflows_activity", Arrays.asList("db/flow_workflows_activity/activity1.json",
        "db/flow_workflows_activity/activity2.json", "db/flow_workflows_activity/activity3.json",
        "db/flow_workflows_activity/activity4.json", "db/flow_workflows_activity/activity5.json",
        "db/flow_workflows_activity/activity6.json"));

    data.put("flow_workflows_activity_task",
        Arrays.asList("db/flow_workflows_activity_task/activityTask1.json"));

    data.put("flow_task_templates",
        Arrays.asList("db/flow_task_templates/template1.json",
            "db/flow_task_templates/template2.json", "db/flow_task_templates/template3.json",
            "db/flow_task_templates/template4.json", "db/flow_task_templates/template5.json",
            "db/flow_task_templates/template6.json"));

    data.put("flow_teams", Arrays.asList("db/flow_teams/team1.json", "db/flow_teams/team2.json",
        "db/flow_teams/team3.json"));

    data.put("flow_settings", Arrays.asList("db/flow_settings/setting1.json",
        "db/flow_settings/setting4.json", "db/flow_settings/setting5.json"));

    data.put("flow_workflows",
        Arrays.asList("db/flow_workflows/workflow1.json", "db/flow_workflows/workflow2.json",
            "db/flow_workflows/workflow3.json", "db/flow_workflows/workflow4.json",
            "db/flow_workflows/workflow5.json", "db/flow_workflows/workflow6.json",
            "db/flow_workflows/workflow7.json", "db/flow_workflows/workflow8.json",
            "db/flow_workflows/workflow9.json", "db/flow_workflows/workflow10.json",
            "db/flow_workflows/workflow11.json", "db/flow_workflows/workflow12.json",
            "db/flow_workflows/workflow13.json", "db/flow_workflows/workflow14.json",
            "db/flow_workflows/workflow15.json", "db/flow_workflows/workflow16.json",
            "scenarios/complex/workflow.json", "scenarios/emptySwitchCondition/workflow.json"));

    data.put("flow_workflows_revisions", Arrays.asList("db/flow_workflows_revisions/revision1.json",
        "db/flow_workflows_revisions/revision2.json", "db/flow_workflows_revisions/revision3.json",
        "db/flow_workflows_revisions/revision4.json", "db/flow_workflows_revisions/revision5.json",
        "db/flow_workflows_revisions/revision6.json", "scenarios/complex/revision.json",
        "scenarios/emptySwitchCondition/revision.json"));

    return data;
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();

    mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

    mockServer
        .expect(manyTimes(),
            requestTo(
                containsString("http://localhost:8085/admin/teams/team/5cedb53261a23a0001e4c1b6")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/admin/teams.json"), MediaType.APPLICATION_JSON));


    mockServer
        .expect(manyTimes(),
            requestTo(containsString("http://localhost:8084/internal/users/user/")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));

    mockServer
        .expect(manyTimes(), requestTo(containsString("http://localhost:8084/internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
  }
}
