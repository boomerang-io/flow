package io.boomerang.scenarios;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.workflow.model.Action;
import io.boomerang.workflow.model.FlowActivity;
import io.boomerang.core.enums.UserType;
import io.boomerang.tests.IntegrationTests;
import io.boomerang.v3.mongo.model.TaskStatus;
import io.boomerang.v3.mongo.model.TokenScope;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
class ManualTaskExecuteTests extends IntegrationTests {

  @MockitoBean private IdentityService service;

  @Test
  void testExecution() throws Exception {

    UserEntity user = new UserEntity();
    user.setEmail("amhudson@us.ibm.com");
    user.setName("Adrienne Hudson");
    user.setType(UserType.admin);

    when(service.getCurrentScope()).thenReturn(PermissionAccess.user);
    when(service.getCurrentUser()).thenReturn(user);

    String workflowId = "5f4fc9e95683833cf0b1335b";
    FlowActivity activity = submitWorkflow(workflowId);
    String id = activity.getId();
    Thread.sleep(5000);
    FlowActivity waitingAprpoval = this.checkWorkflowActivity(id);
    assertEquals(TaskStatus.inProgress, waitingAprpoval.getStatus());
    List<Action> approvals = this.getApprovals();
    assertEquals("Mark down here manual", approvals.get(0).getInstructions());

    this.approveWorkflow(true, approvals.get(0).getId());

    Thread.sleep(5000);
    FlowActivity finalActivity = this.checkWorkflowActivity(id);
    assertEquals(TaskStatus.completed, finalActivity.getStatus());
    mockServer.verify();
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(this.restTemplate).ignoreExpectOrder(true).build();

    // mockServer.expect(manyTimes(), requestTo(containsString("internal/users/user")))
    // .andExpect(method(HttpMethod.GET)).andRespond(
    // withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));

    mockServer
        .expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK));
  }

  @Override
  protected void getTestCaseData(Map<String, List<String>> data) {
    data.put("flow_workflows", Arrays.asList("tests/scenarios/manual/manual-workflow.json"));
    data.put(
        "flow_workflows_revisions",
        Arrays.asList(
            "tests/scenarios/manual/manual-revision1.json",
            "tests/scenarios/manual/manual-revision2.json"));
  }
}
