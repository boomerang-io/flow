package io.boomerang.scenarios;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import io.boomerang.workflow.model.TaskExecutionResponse;
import io.boomerang.tests.IntegrationTests;
import io.boomerang.v3.mongo.model.TaskStatus;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class SucessBlueBranchFlowExecutionTest extends IntegrationTests {

  @Test
  public void testExecution() throws Exception {
    String workflowId = "5d72be2ce7a4aa00072f9ee3";

    FlowExecutionRequest request = new FlowExecutionRequest();

    Map<String, String> inputs = new HashMap<String, String>();
    request.setProperties(inputs);

    FlowActivity activity = submitWorkflow(workflowId, request);

    String id = activity.getId();
    Thread.sleep(10000);
    FlowActivity finalActivity = this.checkWorkflowActivity(id);
     assertEquals(TaskStatus.completed, finalActivity.getStatus());
     assertNotNull(finalActivity.getDuration());
    mockServer.verify();


    List<TaskExecutionResponse> steps = finalActivity.getSteps();
    TaskExecutionResponse executeShell1 = steps.stream()
        .filter(e -> e.getTaskName().equals("Execute Shell 1")).findFirst().orElse(null);
    TaskExecutionResponse executeShell2 = steps.stream()
        .filter(e -> e.getTaskName().equals("Execute Shell 2")).findFirst().orElse(null);
    TaskExecutionResponse executeShell3 = steps.stream()
        .filter(e -> e.getTaskName().equals("Execute Shell 3")).findFirst().orElse(null);
    TaskExecutionResponse switchStep =
        steps.stream().filter(e -> e.getTaskName().equals("Switch 1")).findFirst().orElse(null);

     assertEquals(TaskStatus.completed, executeShell1.getFlowTaskStatus());
     assertEquals(TaskStatus.skipped, executeShell2.getFlowTaskStatus());
     assertEquals(TaskStatus.completed, executeShell3.getFlowTaskStatus());
     assertEquals(TaskStatus.completed, switchStep.getFlowTaskStatus());

     assertEquals("blue", switchStep.getSwitchValue());
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(this.restTemplate).ignoreExpectOrder(true).build();
    mockServer
        .expect(manyTimes(), requestTo(containsString("http://localhost:8084/internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
    mockServer.expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
    mockServer.expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(jsonPath("$.taskName").value("Execute Shell 1"))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
    mockServer.expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(jsonPath("$.taskName").value("Execute Shell 3"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(getMockFile("tests/scenarios/branch/branch-response1.json"),
            MediaType.APPLICATION_JSON));

    mockServer.expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
  }

  @Override
  protected void getTestCaseData(Map<String, List<String>> data) {
    data.put("flow_workflows", Arrays.asList("tests/scenarios/branch/branch-workflow.json"));
    data.put("flow_workflows_revisions",
        Arrays.asList("tests/scenarios/branch/branch-revision1.json"));
  }

}

