package io.boomerang.service.crud;


import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.SchedulerException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import com.cronutils.mapper.CronMapper;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import io.boomerang.model.CronValidationResponse;
import io.boomerang.model.WorkflowSchedule;
import io.boomerang.model.WorkflowScheduleCalendar;
import io.boomerang.mongo.entity.WorkflowEntity;
import io.boomerang.mongo.entity.WorkflowScheduleEntity;
import io.boomerang.mongo.model.KeyValuePair;
import io.boomerang.mongo.model.WorkflowScheduleStatus;
import io.boomerang.mongo.model.WorkflowScheduleType;
import io.boomerang.mongo.service.FlowWorkflowService;
import io.boomerang.mongo.service.ScheduleService;
import io.boomerang.quartz.QuartzSchedulerService;
import io.boomerang.service.FilterService;
import io.boomerang.util.ParameterMapper;

/*
 * Workflow Schedule Serivce provides all the methods for both the Schedules page and the individual Workflow Schedule
 * and abstracts the quartz implementation.
 * 
 * @since Flow 3.6.0
 */
@Service
public class WorkflowScheduleServiceImpl implements WorkflowScheduleService {

  private final Logger logger = LogManager.getLogger(getClass());

  @Autowired
  private QuartzSchedulerService taskScheduler;

  @Autowired
  private ScheduleService workflowScheduleRepository;

  @Autowired
  private FlowWorkflowService workflowRepository;
  
  @Autowired
  private FilterService filterService;

  private static final String DEFAULT_SCHEDULE_TIMEZONE = "UTC";

  private void normalizeCronTimezone(WorkflowScheduleEntity scheduleEntity) {
    if (WorkflowScheduleType.cron.equals(scheduleEntity.getType())) {
      String timezone = scheduleEntity.getTimezone();
      if (timezone == null || timezone.trim().isEmpty()) {
        scheduleEntity.setTimezone(DEFAULT_SCHEDULE_TIMEZONE);
      }
    }
  }
  
  /*
   * Provides an all encompassing schedule retrieval method with optional filters. Ignores deleted schedules.
   * 
   * @return list of Workflow Schedules
   */
  @Override
  public List<WorkflowSchedule> getSchedules(Optional<List<String>> workflowIds,
      Optional<List<String>> teamIds, Optional<List<String>> statuses, Optional<List<String>> types,
      Optional<List<String>> scopes) {
    List<WorkflowSchedule> schedules = new LinkedList<>();

    List<String> filteredWorkflowIds = filterService.getFilteredWorkflowIds(workflowIds, teamIds, scopes);
    final List<WorkflowScheduleEntity> entities = workflowScheduleRepository.getAllSchedulesNotCompletedOrDeleted(filteredWorkflowIds, statuses, types);
    if (entities != null) {
      entities.forEach(e -> {
        schedules.add(convertScheduleEntityToModel(e));
      });
    }
    return schedules;
  }
  
  /*
   * Retrieves all non deleted schedules for a specific workflow.
   * 
   * @return list of Workflow Schedules
   */
  @Override
  public List<WorkflowSchedule> getSchedulesForWorkflow(String workflowId) {
    List<WorkflowSchedule> schedules = new LinkedList<>();
    final List<WorkflowScheduleEntity> entities = workflowScheduleRepository.getSchedulesForWorkflowNotCompletedOrDeleted(workflowId);
    if (entities != null) {
      entities.forEach(e -> {
        schedules.add(convertScheduleEntityToModel(e));
      });
    }
    return schedules;
  }
  
  /*
   * Retrieves a specific schedule
   * 
   * @return a single Workflow Schedule
   */
  @Override
  public WorkflowSchedule getSchedule(String scheduleId) {
    final WorkflowScheduleEntity scheduleEntity = workflowScheduleRepository.getSchedule(scheduleId);
    if (scheduleEntity != null) {
      return convertScheduleEntityToModel(scheduleEntity);
    }
    return null;
  }
  
  /*
   * Helper method to convert from Entity to Model as well as adding in the next schedule date.
   * 
   * @return the single returnable schedule.
   */
  private WorkflowSchedule convertScheduleEntityToModel(WorkflowScheduleEntity entity) {
    WorkflowSchedule schedule = new WorkflowSchedule(entity);
    try {
      schedule.setNextScheduleDate(this.taskScheduler.getNextTriggerDate(entity));
    } catch (Exception e) {
      logger.info("Unable to retrieve next schedule date for {}, skipping.", entity.getId());
    }
    return schedule;
  }
  
  /*
   * Retrieves the calendar dates between a start and end date period for the schedules provided.
   * 
   * @return list of Schedule Calendars
   */
  @Override
  public List<WorkflowScheduleCalendar> getCalendarsForSchedules(final List<String> scheduleIds, Date fromDate, Date toDate) {
    List<WorkflowScheduleCalendar> scheduleCalendars = new LinkedList<>();
    final List<WorkflowScheduleEntity> scheduleEntities = workflowScheduleRepository.getSchedulesNotCompletedOrDeleted(scheduleIds);
    if (scheduleEntities != null) {
      scheduleEntities.forEach(e -> {
        WorkflowScheduleCalendar scheduleCalendar = new WorkflowScheduleCalendar();
        scheduleCalendar.setScheduleId(e.getId());
        scheduleCalendar.setDates(getCalendarForDates(e.getId(), fromDate, toDate));
        scheduleCalendars.add(scheduleCalendar);
      });
    }
    return scheduleCalendars;
  }
  
  /*
   * Retrieves the calendar dates between a start and end date period for a specific workflow
   * 
   * @return list of Schedule Calendars
   */
  @Override
  public List<WorkflowScheduleCalendar> getCalendarsForWorkflow(final String workflowId, Date fromDate, Date toDate) {
    List<WorkflowScheduleCalendar> scheduleCalendars = new LinkedList<>();
    final List<WorkflowScheduleEntity> scheduleEntities = workflowScheduleRepository.getSchedulesForWorkflowNotCompletedOrDeleted(workflowId);
    if (scheduleEntities != null) {
      scheduleEntities.forEach(e -> {
        WorkflowScheduleCalendar scheduleCalendar = new WorkflowScheduleCalendar();
        scheduleCalendar.setScheduleId(e.getId());
        scheduleCalendar.setDates(getCalendarForDates(e.getId(), fromDate, toDate));
        scheduleCalendars.add(scheduleCalendar);
      });
    }
    return scheduleCalendars;
  }
  
  /*
   * Retrieves the calendar dates between a start and end date period for a specific schedule
   * 
   * @return a list of dates for a single Schedule Calendar
   */
  @Override
  public List<Date> getCalendarForDates(final String scheduleId, Date fromDate, Date toDate) {
    final WorkflowScheduleEntity scheduleEntity = workflowScheduleRepository.getSchedule(scheduleId);
    if (scheduleEntity != null) {
      try {
        return this.taskScheduler.getJobTriggerDates(scheduleEntity, fromDate, toDate);
      } catch (Exception e) {
        // Trap exception as we still want to return the dates that we can
        e.printStackTrace();
        logger.info("Unable to retrieve calendar for Schedule: {}, skipping.", scheduleEntity.getId());
      }
    }
    return new LinkedList<>();
  }
  
  /*
   * Create a schedule based on the payload which includes the Workflow Id.
   * 
   * @return echos the created schedule
   */
  @Override
  public WorkflowSchedule createSchedule(final WorkflowSchedule schedule) {
//  TODO: do we have to check if they have authorization to create a workflow against that team?
//  TODO: do we have to check if any of the elements on the Schedule are invalid? such as the cron?
//  TODO: return an error if the minimum required fields arent provided.

    if (schedule != null && schedule.getWorkflowId() != null) {
      WorkflowEntity wfEntity = workflowRepository.getWorkflow(schedule.getWorkflowId());
      if (wfEntity != null) {
          schedule.setCreationDate(new Date());
          WorkflowScheduleEntity scheduleEntity = new WorkflowScheduleEntity();
          BeanUtils.copyProperties(schedule, scheduleEntity);
          if (schedule.getParametersMap() != null && !schedule.getParametersMap().isEmpty()) {
            List<KeyValuePair> propertyList = ParameterMapper.mapToKeyValuePairList(schedule.getParametersMap());
            scheduleEntity.setParameters(propertyList);
          }
          normalizeCronTimezone(scheduleEntity);
          Boolean enableJob = false;
          if (WorkflowScheduleStatus.active.equals(schedule.getStatus())) {
            if (!wfEntity.getTriggers().getScheduler().getEnable()) {
              // Workflow's scheduler trigger is disabled — mark schedule accordingly
              scheduleEntity.setStatus(WorkflowScheduleStatus.trigger_disabled);
            } else {
              // Trigger is enabled — keep active status and enable the Quartz job
              enableJob = true;
            }
          }
          workflowScheduleRepository.saveSchedule(scheduleEntity);
          createOrUpdateSchedule(scheduleEntity, enableJob);
          return new WorkflowSchedule(scheduleEntity);
        }
      }
    return null;
  }
  
  /*
   * Update a schedule based on the payload and the Schedules Id.
   * 
   * @return echos the updated schedule
   */
  @Override
  public WorkflowSchedule updateSchedule(final String scheduleId, final WorkflowSchedule patchSchedule) {
    if (patchSchedule != null) {
      WorkflowScheduleEntity scheduleEntity = workflowScheduleRepository.getSchedule(scheduleId);
      if (scheduleEntity != null) {
        /*
         * The copy ignores certain fields
         * - ID and creationDate to ensure data integrity
         * - parameters and parametersMap due to the need to convert the property structure
         */
        WorkflowScheduleStatus previousStatus = scheduleEntity.getStatus();
        BeanUtils.copyProperties(patchSchedule, scheduleEntity, "id", "creationDate", "parameters", "parametersMap");
        if (patchSchedule.getParametersMap() != null && !patchSchedule.getParametersMap().isEmpty()) {
          List<KeyValuePair> propertyList = ParameterMapper.mapToKeyValuePairList(patchSchedule.getParametersMap());
          scheduleEntity.setParameters(propertyList);
        }
        normalizeCronTimezone(scheduleEntity);
        
        /*
         * Complex Status checking to determine what can and can't be enabled
         */
        //Check if job is trying to be enabled with date in the past
        WorkflowScheduleStatus newStatus = scheduleEntity.getStatus();
        WorkflowEntity wfEntity = workflowRepository.getWorkflow(scheduleEntity.getWorkflowId());
        Boolean enableJob = true;
        if (!previousStatus.equals(newStatus)) {
          if (WorkflowScheduleStatus.active.equals(previousStatus) && WorkflowScheduleStatus.inactive.equals(newStatus)) {
            scheduleEntity.setStatus(WorkflowScheduleStatus.inactive);
            enableJob = false;
          } else if (WorkflowScheduleStatus.inactive.equals(previousStatus) && WorkflowScheduleStatus.active.equals(newStatus)) {
            if (wfEntity != null && !wfEntity.getTriggers().getScheduler().getEnable()) {
              scheduleEntity.setStatus(WorkflowScheduleStatus.trigger_disabled);
              enableJob = false;
            }
            if (WorkflowScheduleType.runOnce.equals(scheduleEntity.getType())) {
              Date currentDate = new Date();
              if (scheduleEntity.getDateSchedule().getTime() < currentDate.getTime()) {
                logger.info("Cannot enable schedule (" + scheduleEntity.getId() + ") as it is in the past.");
                scheduleEntity.setStatus(WorkflowScheduleStatus.error);
                workflowScheduleRepository.saveSchedule(scheduleEntity);
                try {
                  this.taskScheduler.cancelJob(scheduleEntity);
                } catch (SchedulerException e) {
                  e.printStackTrace();
                }
                return new WorkflowSchedule(scheduleEntity);
              }
            }
          }
        } else {
          if (WorkflowScheduleStatus.inactive.equals(newStatus)) {
            enableJob = false;
          }
        }
        workflowScheduleRepository.saveSchedule(scheduleEntity);
        createOrUpdateSchedule(scheduleEntity, enableJob);
        return new WorkflowSchedule(scheduleEntity);
      }
    }
    return null;
  }

  /*
   * Helper method to determine if we are updating a cron or runonce schedule. It also handles
   * pausing a schedule if the status is set to pause.
   */
  private void createOrUpdateSchedule(final WorkflowScheduleEntity schedule, Boolean enableJob) {
    try {
      if (WorkflowScheduleType.runOnce.equals(schedule.getType())) {
        this.taskScheduler.createOrUpdateRunOnceJob(schedule);
      } else {
        this.taskScheduler.createOrUpdateCronJob(schedule);
      }
      if (!enableJob) {
        this.taskScheduler.pauseJob(schedule);
      }
    } catch (SchedulerException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  
  /*
   * Enables all schedules that have been disabled by the trigger being disabled. This is needed to 
   * differentiate between user paused and trigger disabled schedules.
   */
  @Override
  public void enableAllTriggerSchedules(final String workflowId) {
    final List<WorkflowScheduleEntity> entities = workflowScheduleRepository.getSchedulesForWorkflowWithStatus(workflowId, WorkflowScheduleStatus.trigger_disabled);
    if (entities != null) {
      entities.forEach(s -> {
        try {
          enableSchedule(s.getId());
        } catch (SchedulerException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      });
    }
  }
  
  /* 
   * Enables a specific schedule
   */
  private void enableSchedule(String scheduleId) throws SchedulerException {
    WorkflowScheduleEntity schedule = workflowScheduleRepository.getSchedule(scheduleId);
    if (schedule!= null && !WorkflowScheduleStatus.deleted.equals(schedule.getStatus())) {
      if (WorkflowScheduleType.runOnce.equals(schedule.getType())) {
        Date currentDate = new Date();
        logger.info("Current DateTime: ", currentDate.getTime());
        logger.info("Schedule DateTime: ", schedule.getDateSchedule().getTime());
        if (schedule.getDateSchedule().getTime() < currentDate.getTime()) {
          logger.info("Cannot enable schedule (" + schedule.getId() + ") as it is in the past.");
          schedule.setStatus(WorkflowScheduleStatus.error);
        } else {
          schedule.setStatus(WorkflowScheduleStatus.active);
          workflowScheduleRepository.saveSchedule(schedule);
          this.taskScheduler.resumeJob(schedule);
        }
      } else {
        schedule.setStatus(WorkflowScheduleStatus.active);
        workflowScheduleRepository.saveSchedule(schedule);
        this.taskScheduler.resumeJob(schedule);
      }
    }
  }
  
  /*
   * Disables all schedules that are currently active and is used when the trigger is disabled.
   */
  @Override
  public void disableAllTriggerSchedules(final String workflowId) {
    final List<WorkflowScheduleEntity> entities = workflowScheduleRepository.getSchedulesForWorkflowWithStatus(workflowId, WorkflowScheduleStatus.active);
    if (entities != null) {
      entities.forEach(s -> {
        try {
          WorkflowScheduleEntity schedule = workflowScheduleRepository.getSchedule(s.getId());
          if (schedule!= null) {
            schedule.setStatus(WorkflowScheduleStatus.trigger_disabled);
            workflowScheduleRepository.saveSchedule(schedule);
            this.taskScheduler.pauseJob(schedule);
          }
        } catch (SchedulerException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      });
    }
  }
  
  /*
   * Disable a specific schedule
   */
  private void disableSchedule(String scheduleId) throws SchedulerException {
    WorkflowScheduleEntity schedule = workflowScheduleRepository.getSchedule(scheduleId);
    if (schedule!= null && !WorkflowScheduleStatus.deleted.equals(schedule.getStatus())) {
      schedule.setStatus(WorkflowScheduleStatus.inactive);
      workflowScheduleRepository.saveSchedule(schedule);
      this.taskScheduler.pauseJob(schedule);
    } else {
//        TODO: return that it couldn't be disabled or doesn't exist
    }
  }
  
  /*
   * Disable a specific schedule
   */
  @Override
  public ResponseEntity<?> completeSchedule(String scheduleId) {
    WorkflowScheduleEntity schedule = workflowScheduleRepository.getSchedule(scheduleId);
    if (schedule!= null && !WorkflowScheduleStatus.deleted.equals(schedule.getStatus())) {
      schedule.setStatus(WorkflowScheduleStatus.completed);
      try {
        workflowScheduleRepository.saveSchedule(schedule);
        this.taskScheduler.pauseJob(schedule);
      } catch (SchedulerException e) {
        logger.info("Unable to delete schedule {}.", scheduleId);
        logger.error(e);
        return ResponseEntity.internalServerError().build();
      }
    } else {
//        TODO: return that it couldn't be disabled or doesn't exist
    }
    return ResponseEntity.ok().build();
  }
  
  /*
   * Mark all schedules as deleted and cancel the quartz jobs. This is used when a workflow is deleted.
   */
  @Override
  public void deleteAllSchedules(final String workflowId) {
//    TODO: get this integrated with the deleteWorkflow
    final List<WorkflowScheduleEntity> entities = workflowScheduleRepository.getSchedulesForWorkflow(workflowId);
    if (entities != null) {
      entities.forEach(s -> {
        deleteSchedule(s.getId());
      });
    }
  }
  
  /*
   * Mark a single schedule as deleted and cancel the quartz jobs. Used by the UI when deleting a schedule.
   */
  @Override
  public ResponseEntity<?> deleteSchedule(final String scheduleId) {
    try {
      WorkflowScheduleEntity scheduleEntity = workflowScheduleRepository.getSchedule(scheduleId);
      if (scheduleEntity != null) {
        scheduleEntity.setStatus(WorkflowScheduleStatus.deleted);
        workflowScheduleRepository.saveSchedule(scheduleEntity);
        this.taskScheduler.cancelJob(scheduleEntity);
      } else {
        return ResponseEntity.badRequest().build();
      }
    } catch (SchedulerException e) {
      logger.info("Unable to delete schedule {}.", scheduleId);
      logger.error(e);
      return ResponseEntity.internalServerError().build();
    }
    return ResponseEntity.ok().build();
  }
  
  /*
   * Helper method to validate the cron provided by the user.
   * 
   * @since 3.4.0
   * @return a cron validation response.
   */
  @Override
  public CronValidationResponse validateCron(String cronString) {

    logger.info("CRON: {}", cronString);

    CronValidationResponse response = new CronValidationResponse();
    CronParser parser =
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    try {
      cronString = parser.parse(cronString).asString();
      response.setCron(cronString);
      response.setValid(true);
      logger.info("Final CRON: {} .", cronString);
    } catch (IllegalArgumentException e) {
      logger.info("Invalid CRON: {} . Attempting cron to quartz conversion", cronString);
      parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.CRON4J));
      try {
        Cron cron = parser.parse(cronString);
        CronMapper quartzMapper = CronMapper.fromCron4jToQuartz();
        Cron quartzCron = quartzMapper.map(cron);
        cronString = quartzCron.asString();
        response.setCron(cronString);
        response.setValid(true);
      } catch (IllegalArgumentException exc) {
        logger.info("Invalid CRON: {} . Cannot convert", cronString);
        response.setCron(null);
        response.setValid(false);
        response.setMessage(e.getMessage());
      }

      logger.info("Final CRON: {} .", cronString);
    }
    return response;
  }
}
