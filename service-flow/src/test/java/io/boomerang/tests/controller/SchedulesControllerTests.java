package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.misc.FlowTests;
import io.boomerang.workflow.ScheduleService;
import io.boomerang.workflow.SchedulesV2Controller;
import io.boomerang.workflow.model.CronValidationResponse;
import java.util.Calendar;
import java.util.Date;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class SchedulesControllerTests extends FlowTests {

  @Autowired private SchedulesV2Controller controller;

  @Autowired private ScheduleService workflowScheduleService;

  @Test
  /*
   * Test creates a new RunOnce schedule 7 days in the future Similar to the method used in
   * TaskServiceImpl for the Run Scheduled Workflow System Task
   */
  public void testCreateWorkflowSchedule() {

    WorkflowSchedule newSchedule = new WorkflowSchedule();
    newSchedule.setName("Test Schedule");
    newSchedule.setType(WorkflowScheduleType.runOnce);
    newSchedule.setStatus(WorkflowScheduleStatus.active);
    newSchedule.setTimezone("Australia/Melbourne");
    newSchedule.setWorkflowRef("5d1a188af6ca2c00014c4314");
    Date executionDate = new Date();
    Calendar executionCal = Calendar.getInstance();
    executionCal.setTime(executionDate);
    executionCal.add(Calendar.DATE, 7);
    newSchedule.setDateSchedule(executionCal.getTime());

    WorkflowSchedule savedSchedule = workflowScheduleService.create(newSchedule);
    Assertions.assertEquals(WorkflowScheduleStatus.active, savedSchedule.getStatus());
    Assertions.assertEquals(WorkflowScheduleType.runOnce, savedSchedule.getType());
    Assertions.assertEquals(executionCal.getTime(), savedSchedule.getDateSchedule());
  }

  @Test
  public void testCronValidation() {
    CronValidationResponse response = controller.validateCron("0 * * * * *");
    assertEquals(false, response.isValid());
    assertEquals(null, response.getCron());
    assertEquals(
        "Failed to parse cron expression. Invalid cron expression: 0 * * * * *. Both, a day-of-week AND a day-of-month parameter, are not supported.",
        response.getMessage());

    response = controller.validateCron("0 * * ? * *");
    assertEquals(true, response.isValid());
    assertEquals("0 * * ? * *", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("0 0 * ? * *");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * ? * *", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("0 0 * ? * MON,TUE,WED,THU,FRI,SAT,SUN");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * ? * 2,3,4,5,6,7,1", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("5 0 * 8 *");
    assertEquals(true, response.isValid());
    assertEquals("0 5 0 * 8 ? *", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("0 * * * *");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * * * ? *", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("* * * * *");
    assertEquals(true, response.isValid());
    assertEquals("0 * * * * ? *", response.getCron());
    assertEquals(null, response.getMessage());

    response = controller.validateCron("1 1 1 1 1");
    assertEquals(false, response.isValid());
    assertEquals(null, response.getCron());
    assertEquals(
        "Cron expression contains 5 parts but we expect one of [6, 7]", response.getMessage());
  }
}
