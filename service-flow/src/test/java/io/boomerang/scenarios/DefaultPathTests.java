package io.boomerang.scenarios;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.tests.IntegrationTests;
import io.boomerang.tests.MongoConfig;
import io.boomerang.v3.mongo.model.TaskStatus;
import io.boomerang.workflow.model.Action;
import io.boomerang.workflow.model.FlowActivity;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
<<<<<<< HEAD:src/test/java/io/boomerang/scenarios/DefaultPathTests.java
import io.boomerang.Application;
import io.boomerang.model.FlowActivity;
import io.boomerang.model.teams.Action;
import io.boomerang.mongo.model.TaskStatus;
import io.boomerang.tests.IntegrationTests;
=======
>>>>>>> origin/main:service-flow/src/test/java/io/boomerang/scenarios/DefaultPathTests.java

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("test")
@Disabled
public class DefaultPathTests extends IntegrationTests {

  @Test
  public void testExecution() throws Exception {
    String workflowId = "5f74bf14eca6295e3e10aaea";

    FlowActivity activity = submitWorkflow(workflowId);

    String id = activity.getId();
    Thread.sleep(5000);

    FlowActivity waitingAprpoval = this.checkWorkflowActivity(id);
    assertEquals(TaskStatus.inProgress, waitingAprpoval.getStatus());
    List<Action> approvals = this.getApprovals();
    this.approveWorkflow(true, approvals.get(0).getId());

    Thread.sleep(5000);

    FlowActivity activit2 = this.checkWorkflowActivity(activity.getId());

    mockServer.verify();

    assertNotNull(activit2);
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(this.restTemplate).ignoreExpectOrder(true).build();

    mockServer
        .expect(times(4), requestTo(containsString("internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));

    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("users/user/5e736fb0a97b78000125ebe3")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));

    mockServer
        .expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));
  }

  @Override
  protected void getTestCaseData(Map<String, List<String>> data) {
    data.put("flow_workflows", Arrays.asList("tests/scenarios/default/default-workflow.json"));
    data.put(
        "flow_workflows_revisions",
        Arrays.asList("tests/scenarios/default/default-revision1.json"));
  }
}
