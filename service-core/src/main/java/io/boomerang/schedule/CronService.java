package io.boomerang.schedule;

import com.cronutils.mapper.CronMapper;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.schedule.model.CronValidationResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CronService {

  private static final CronParser UNIX_PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
  private static final CronParser QUARTZ_PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
  // "0 mm HH * DAYS": the order the schedule form saved day-and-time schedules in before it wrote UNIX.
  private static final Pattern FORM_DAY_AND_TIME = Pattern.compile("0 (\\d{1,2}) (\\d{1,2}) \\* (\\S+)");

  /**
   * Return {@code cron} in the five-field UNIX form the scheduler reads (minute, hour, day of month,
   * month, day of week). A six- or seven-field Quartz expression (seconds first, as v3 and v4
   * stored them) is mapped; for a {@code cron} schedule the "0 mm HH * DAYS" order an earlier form
   * saved is reordered - only for that type, since "0 9 1 * *" is also a valid monthly UNIX cron.
   * A UNIX expression comes back as written. Null when the expression can't be read.
   */
  public String toUnix(WorkflowScheduleType type, String cron) {
    if (cron == null || cron.isBlank()) {
      return null;
    }
    String expression = cron.trim().replaceAll("\\s+", " ");
    try {
      if (expression.split(" ").length > 5) {
        return CronMapper.fromQuartzToUnix().map(QUARTZ_PARSER.parse(expression)).asString();
      }
      Matcher form = FORM_DAY_AND_TIME.matcher(expression);
      if (WorkflowScheduleType.cron.equals(type) && form.matches()) {
        expression = form.group(1) + " " + form.group(2) + " * * " + form.group(3);
      }
      UNIX_PARSER.parse(expression);
      return expression;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

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
