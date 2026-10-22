package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.error.BoomerangException;
import io.boomerang.misc.FlowTests;
import io.boomerang.workflow.ExecutionController;
import io.boomerang.workflow.model.FlowActivity;
import io.boomerang.workflow.model.FlowExecutionRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class ExecutionControllerTests extends FlowTests {

  @Autowired protected ExecutionController executionController;

  @Test
  public void testExecuteWorkflowNotActive() {
    String workflowId = "5d1a188af6ca2c00014c4369"; // workflow13.json

    FlowActivity activity =
        executionController.executeWorkflow(
            workflowId,
            Optional.of(TriggerEnum.manual.toString()),
            Optional.of(new FlowExecutionRequest()));

    assertNull(activity);
  }

  @Test
  public void testExecuteWorkflowExceedQuotaMax() {
    try {
      executionController.executeWorkflow(
          "5d1a188af6ca2c00014c4314", // workflow1.json
          Optional.of(TriggerEnum.manual.toString()),
          Optional.of(new FlowExecutionRequest()));
    } catch (BoomerangException e) {
      assertEquals(429, e.getCode());
      assertEquals("TOO_MANY_REQUESTS", e.getDescription());
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getHttpStatus());
    }
  }
}
