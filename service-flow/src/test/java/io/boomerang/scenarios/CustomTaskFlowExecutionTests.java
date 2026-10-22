package io.boomerang.scenarios;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import io.boomerang.workflow.model.FlowActivity;
import io.boomerang.workflow.model.FlowExecutionRequest;
import io.boomerang.agent.FlowActivityService;
import io.boomerang.tests.IntegrationTests;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
class CustomTaskFlowExecutionTests extends IntegrationTests {

  @Autowired protected FlowActivityService activityService;

  @Test
  void testExecuteFlow() throws InterruptedException, ExecutionException {

    String workflowId = "5d7177af2c57250007e3d7a9";

    Map<String, String> properties = new HashMap<String, String>();
    properties.put("foo", "hello");
    properties.put("bar", "world");
    FlowExecutionRequest flowRequest = new FlowExecutionRequest();
    flowRequest.setProperties(properties);

    FlowActivity activity = submitWorkflow(workflowId, flowRequest);

    String id = activity.getId();
    Thread.sleep(5000);
    FlowActivity finalActivity = this.checkWorkflowActivity(id);

    assertNotNull(finalActivity.getDuration());
    mockServer.verify();
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    mockServer
        .expect(manyTimes(), requestTo(containsString("http://localhost:8084/internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.workflowName").value("Unit Test Demo"))
        .andExpect(jsonPath("$.taskType").value("template"))
        .andExpect(jsonPath("$.taskName").value("Echo Test"))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.workflowName").value("Unit Test Demo"))
        .andExpect(jsonPath("$.taskType").value("template"))
        .andExpect(jsonPath("$.command").value("world"))
        .andExpect(jsonPath("$.image").value("busybox"))
        .andExpect(jsonPath("$.arguments").value("hello"))
        .andExpect(jsonPath("$.taskName").value("Custom Task 1"))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));
  }

  @Override
  protected void getTestCaseData(Map<String, List<String>> data) {
    data.put("flow_workflows", Arrays.asList("tests/scenarios/custom/custom-workflow.json"));
    data.put(
        "flow_workflows_revisions", Arrays.asList("tests/scenarios/custom/custom-revision1.json"));
  }
}
