package io.boomerang.workflow;

import com.cronutils.mapper.CronMapper;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import io.boomerang.workflow.model.CronValidationResponse;
import org.springframework.stereotype.Component;

@Component
public class CronService {

  /*
   * Helper method to validate the cron provided by the user.
   *
   * @since 3.4.0
   * @return a cron validation response.
   */
  public CronValidationResponse validateCron(String cronString) {

    CronValidationResponse response = new CronValidationResponse();
    CronParser parser =
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    try {
      cronString = parser.parse(cronString).asString();
      response.setCron(cronString);
      response.setValid(true);
    } catch (IllegalArgumentException e) {
      parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.CRON4J));
      try {
        Cron cron = parser.parse(cronString);
        CronMapper cronMapper = CronMapper.fromCron4jToQuartz();
        Cron quartzCron = cronMapper.map(cron);
        cronString = quartzCron.asString();
        response.setCron(cronString);
        response.setValid(true);
      } catch (IllegalArgumentException exc) {
        response.setCron(null);
        response.setValid(false);
        response.setMessage(e.getMessage());
      }
    }
    return response;
  }
}
