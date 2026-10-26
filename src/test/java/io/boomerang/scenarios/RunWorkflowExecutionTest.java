package io.boomerang.scenarios;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.times;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.mockito.Spy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import io.boomerang.model.FlowActivity;
import io.boomerang.model.FlowWebhookResponse;
import io.boomerang.model.RequestFlowExecution;
import io.boomerang.mongo.model.TaskStatus;
import io.boomerang.service.refactor.TaskClient;
import io.boomerang.tests.IntegrationTests;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
@Disabled
public class RunWorkflowExecutionTest extends IntegrationTests {

  @Spy
  private TaskClient taskClient;

  @Test
  public void testExecution() throws Exception {


    doReturn(null).when(taskClient)
        .submitWebhookEvent(ArgumentMatchers.any(RequestFlowExecution.class));

    String workflowId = "603936f5c3a72a0d655fb337";
    RequestFlowExecution request = new RequestFlowExecution();

    request.setWorkflowId(workflowId);

    Map<String, String> map = new HashMap<>();
    map.put("foobar", "Hello World");
    request.setProperties(map);
    FlowWebhookResponse activity = this.submitInternalWorkflow(workflowId, request);

    String id = activity.getActivityId();
    Thread.sleep(5000);
    FlowActivity finalActivity = this.checkWorkflowActivity(id);
    assertEquals(TaskStatus.completed, finalActivity.getStatus());
    assertNotNull(finalActivity.getDuration());
    mockServer.verify();
    verify(taskClient).submitWebhookEvent(ArgumentMatchers.any(RequestFlowExecution.class));
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(this.restTemplate).ignoreExpectOrder(true).build();
    mockServer.expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
    mockServer.expect(times(1), requestTo(containsString("internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
    mockServer.expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
  }

  @Override
  protected void getTestCaseData(Map<String, List<String>> data) {
    data.put("flow_workflows",
        Arrays.asList("tests/scenarios/runworkflow/runworkflow-workflow.json"));
    data.put("flow_workflows_revisions",
        Arrays.asList("tests/scenarios/runworkflow/runworkflow-revision.json"));
  }

}
