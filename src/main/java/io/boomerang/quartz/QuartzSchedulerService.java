package io.boomerang.quartz;

import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.spi.OperableTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Component;
import io.boomerang.model.CronValidationResponse;
import io.boomerang.mongo.entity.WorkflowScheduleEntity;
import io.boomerang.service.crud.WorkflowScheduleService;

@Component
public class QuartzSchedulerService {

  private final Logger logger = LogManager.getLogger(getClass());

  @Autowired
  private SchedulerFactoryBean schedulerFactoryBean;

  @Autowired
  private WorkflowScheduleService workflowScheduleService;

  /**
   * Get the Quartz Scheduler instance with null-safety check
   */
  private Scheduler getScheduler() throws SchedulerException {
    if (schedulerFactoryBean == null) {
      throw new SchedulerException("SchedulerFactoryBean is not initialized");
    }
    Scheduler scheduler = schedulerFactoryBean.getScheduler();
    if (scheduler == null) {
      throw new SchedulerException("Scheduler could not be obtained from SchedulerFactoryBean. Ensure flow.scheduling.enabled=true");
    }
    if (!scheduler.isStarted()) {
      logger.warn("Scheduler is not started, attempting to start it now");
      try {
        scheduler.start();
      } catch (SchedulerException e) {
        logger.error("Failed to start scheduler", e);
        throw e;
      }
    }
    return scheduler;
  }

  public void createOrUpdateCronJob(WorkflowScheduleEntity schedule) {
    String cronString = schedule.getCronSchedule();
    String timezone = schedule.getTimezone();
    if (cronString != null && timezone != null) {
      boolean validCron = org.quartz.CronExpression.isValidExpression(cronString);
      if (!validCron) {
        logger.info("Invalid CRON: {}. Attempting to convert.", cronString);
        CronValidationResponse response = workflowScheduleService.validateCron(cronString);
        if (response.isValid()) {
          cronString = response.getCron();
          logger.info("CRON converted: {}.", cronString);
        } else {
          logger.info("Invalid CRON: {}.", cronString);
          return;
        }
      }
      TimeZone timeZone = TimeZone.getTimeZone(timezone);
      String scheduleId = schedule.getId();
      String workflowId = schedule.getWorkflowId();
      Scheduler scheduler;
      try {
        scheduler = getScheduler();
      } catch (SchedulerException e) {
        logger.error("Failed to get scheduler instance", e);
        return;
      }
      JobDetail jobDetail =
          JobBuilder.newJob(WorkflowExecuteJob.class).withIdentity(scheduleId, workflowId).build();         
//    TODO: determine if we add a calendar entry for excluded dates
    CronScheduleBuilder cronScheduleBuilder =
        CronScheduleBuilder.cronSchedule(cronString).inTimeZone(timeZone);
    CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(scheduleId, workflowId)
        .withSchedule(cronScheduleBuilder).build();
    
      try {
        if (!scheduler.checkExists(jobDetail.getKey())) {
          scheduler.scheduleJob(jobDetail, trigger);
          logger.info("Scheduled Cron Schedule: {} for Workflow: {}.", scheduleId, workflowId);
        } else {
          scheduler.rescheduleJob(new TriggerKey(scheduleId, workflowId), trigger);
          logger.info("Updated Cron Schedule: {} for Workflow: {}.", scheduleId, workflowId);
        }
      } catch (SchedulerException e1) {
        logger.error("Unable to schedule workflow");
        logger.error(ExceptionUtils.getStackTrace(e1));
      }
    }
  }
  
  public void createOrUpdateRunOnceJob(WorkflowScheduleEntity schedule) throws Exception {
    String scheduleId = schedule.getId();
    String workflowId = schedule.getWorkflowId();
    Scheduler scheduler = getScheduler();
    JobDetail jobDetail = JobBuilder.newJob(WorkflowExecuteJob.class).withIdentity(scheduleId, workflowId).build();
    SimpleScheduleBuilder simpleScheduleBuilder = SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0);
    SimpleTrigger trigger = TriggerBuilder.newTrigger().withIdentity(scheduleId, workflowId).startAt(schedule.getDateSchedule())
        .withSchedule(simpleScheduleBuilder).build();
    try {
      if (!scheduler.checkExists(jobDetail.getKey())) {
          scheduler.scheduleJob(jobDetail, trigger);
          logger.info("Created RunOnce Schedule: {} for Workflow: {}.", scheduleId, workflowId);
      } else {
        scheduler.rescheduleJob(new TriggerKey(schedule.getId(), schedule.getWorkflowId()),trigger);
        logger.info("Updated RunOnce Schedule: {} for Workflow: {}.", scheduleId, workflowId);
      }
    } catch (SchedulerException e1) {
      logger.error("Unable to create or update scheduled job");
      logger.error(ExceptionUtils.getStackTrace(e1));
    }
  }
  
  public void pauseJob(WorkflowScheduleEntity schedule) throws SchedulerException {
    Scheduler scheduler = getScheduler();
    scheduler.pauseJob(new JobKey(schedule.getId(), schedule.getWorkflowId()));
  }
  
  public void resumeJob(WorkflowScheduleEntity schedule) throws SchedulerException {
    Scheduler scheduler = getScheduler();
    scheduler.resumeJob(new JobKey(schedule.getId(), schedule.getWorkflowId()));
  }
  
  public void cancelJob(WorkflowScheduleEntity schedule) throws SchedulerException {
    Scheduler scheduler = getScheduler();
    scheduler.deleteJob(new JobKey(schedule.getId(), schedule.getWorkflowId()));
  }
  
  public List<Date> getJobTriggerDates(WorkflowScheduleEntity schedule, Date fromDate, Date toDate) throws SchedulerException {
    Scheduler scheduler = getScheduler();
    Trigger trigger = scheduler.getTrigger(new TriggerKey(schedule.getId(), schedule.getWorkflowId()));
    logger.info("Retrieving Dates from: " + fromDate.toString() + ", to: " + toDate.toString() + ", for Schedule: " + schedule.getId());
    return org.quartz.TriggerUtils.computeFireTimesBetween((OperableTrigger) trigger, new BaseCalendar(), fromDate, toDate);
  }
  
  public Date getNextTriggerDate(WorkflowScheduleEntity schedule) throws SchedulerException {
    Scheduler scheduler = getScheduler();
    Trigger trigger = scheduler.getTrigger(new TriggerKey(schedule.getId(), schedule.getWorkflowId()));
    logger.info("Retrieving Next Schedule Date for Schedule: " + schedule.getId());
    return trigger.getNextFireTime();
  }
}
