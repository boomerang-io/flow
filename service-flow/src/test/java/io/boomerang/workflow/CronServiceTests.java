package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.workflow.model.CronValidationResponse;
import org.junit.jupiter.api.Test;

/*
 * Uplifted from the v3-era SchedulesControllerTests#testCronValidation — cron validation is pure
 * computation on CronService, so this runs as a plain unit test.
 */
class CronServiceTests {

  private final CronService cronService = new CronService();

  @Test
  void testInvalidQuartzCronWithBothDayFields() {
    CronValidationResponse response = cronService.validateCron("0 * * * * *");
    assertEquals(false, response.isValid());
    assertEquals(null, response.getCron());
  }

  @Test
  void testValidQuartzCron() {
    CronValidationResponse response = cronService.validateCron("0 * * ? * *");
    assertEquals(true, response.isValid());
    assertEquals("0 * * ? * *", response.getCron());
    assertEquals(null, response.getMessage());

    response = cronService.validateCron("0 0 * ? * *");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * ? * *", response.getCron());
    assertEquals(null, response.getMessage());

    response = cronService.validateCron("0 0 * ? * MON,TUE,WED,THU,FRI,SAT,SUN");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * ? * 2,3,4,5,6,7,1", response.getCron());
    assertEquals(null, response.getMessage());
  }

  @Test
  void testCron4jConvertedToQuartz() {
    CronValidationResponse response = cronService.validateCron("5 0 * 8 *");
    assertEquals(true, response.isValid());
    assertEquals("0 5 0 * 8 ? *", response.getCron());
    assertEquals(null, response.getMessage());

    response = cronService.validateCron("0 * * * *");
    assertEquals(true, response.isValid());
    assertEquals("0 0 * * * ? *", response.getCron());
    assertEquals(null, response.getMessage());

    response = cronService.validateCron("* * * * *");
    assertEquals(true, response.isValid());
    assertEquals("0 * * * * ? *", response.getCron());
    assertEquals(null, response.getMessage());
  }

  @Test
  void testInvalidCronWithWrongPartCount() {
    CronValidationResponse response = cronService.validateCron("1 1 1 1 1");
    assertEquals(false, response.isValid());
    assertEquals(null, response.getCron());
  }
}
