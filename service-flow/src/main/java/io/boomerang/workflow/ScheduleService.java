package io.boomerang.workflow;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.boomerang.client.EngineClient;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.workflow.model.WorkflowScheduleCalendar;
import io.boomerang.workflow.repository.WorkflowScheduleRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.CronExpression;
import org.quartz.*;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/*
 * Workflow Schedule Service provides all the methods for both the Schedules page and the individual Workflow Schedule
 * and abstracts the quartz implementation.
 *
 * @since Flow 3.6.0
 */
@Service
public class ScheduleService {

  private final Logger LOGGER = LogManager.getLogger(getClass());

  private final WorkflowScheduleRepository scheduleRepository;
  private final WorkflowService workflowService;
  private final RelationshipService relationshipService;
  private final EngineClient engineClient;
  private final MongoTemplate mongoTemplate;
  private final JobScheduler jobScheduler;
  private final ScheduleJob job;

  public ScheduleService(
      WorkflowScheduleRepository scheduleRepository,
      WorkflowService workflowService,
      RelationshipService relationshipService,
      EngineClient engineClient,
      MongoTemplate mongoTemplate,
      JobScheduler jobScheduler,
      ScheduleJob job) {
    this.jobScheduler = jobScheduler;
    this.job = job;
    this.scheduleRepository = scheduleRepository;
    this.workflowService = workflowService;
    this.relationshipService = relationshipService;
    this.engineClient = engineClient;
    this.mongoTemplate = mongoTemplate;
  }

  /*
   * Retrieves a specific schedule
   *
   * @return a single Workflow Schedule
   */
  public WorkflowSchedule get(String team, String scheduleId) {
    final Optional<WorkflowScheduleEntity> scheduleEntity = scheduleRepository.findById(scheduleId);
    if (scheduleEntity.isPresent()
        && scheduleEntity.get().getWorkflowRef() != null
        && relationshipService.hasNodes(
            RelationshipType.TEAM,
            team,
            RelationshipType.WORKFLOW,
            Optional.of(List.of(scheduleEntity.get().getWorkflowRef())),
            Optional.empty(),
            Optional.empty())) {
      return convertScheduleEntityToModel(scheduleEntity.get());
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  /*
   * Internal Get
   *
   * Used by ExecuteScheduleJob
   */
  public WorkflowSchedule internalGet(String scheduleId) {
    final Optional<WorkflowScheduleEntity> scheduleEntity = scheduleRepository.findById(scheduleId);
    if (scheduleEntity.isPresent()) {
      return convertScheduleEntityToModel(scheduleEntity.get());
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  /*
   * Provides an all encompassing schedule retrieval method with optional filters. Ignores deleted schedules.
   *
   * @return list of Workflow Schedules
   */
  public Page<WorkflowSchedule> query(
      String queryTeam,
      int page,
      int limit,
      Sort sort,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryTypes,
      Optional<List<String>> queryWorkflows) {
    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(queryTeam)),
            false);
    if (!refs.isEmpty()) {
      List<Criteria> criteriaList = new ArrayList<>();
      Criteria criteria = Criteria.where("workflowRef").in(refs);
      criteriaList.add(criteria);

      if (queryStatus.isPresent()) {
        if (queryStatus.get().stream()
            .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(WorkflowScheduleStatus.class, q))) {
          Criteria statusCriteria = Criteria.where("status").in(queryStatus.get());
          criteriaList.add(statusCriteria);
        } else {
          throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
        }
      }

      if (queryTypes.isPresent()) {
        Criteria queryCriteria = Criteria.where("type").in(queryTypes.get());
        criteriaList.add(queryCriteria);
      }

      Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
      Criteria allCriteria = new Criteria();
      if (criteriaArray.length > 0) {
        allCriteria.andOperator(criteriaArray);
      }
      Query query = new Query(allCriteria);
      final Pageable pageable = PageRequest.of(page, limit, sort);
      query.with(pageable);

      List<WorkflowScheduleEntity> scheduleEntities =
          mongoTemplate.find(query.with(pageable), WorkflowScheduleEntity.class);

      List<WorkflowSchedule> workflowSchedules = new LinkedList<>();
      scheduleEntities.forEach(
          e -> {
            workflowSchedules.add(convertScheduleEntityToModel(e));
          });

      Page<WorkflowSchedule> pages =
          PageableExecutionUtils.getPage(
              workflowSchedules,
              pageable,
              () -> mongoTemplate.count(query, WorkflowScheduleEntity.class));
      return pages;
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  /*
   * Create a schedule based on the payload which includes the Workflow Id.
   *
   * @return echos the created schedule
   */
  public WorkflowSchedule create(String team, final WorkflowSchedule schedule) {
    if (schedule != null && schedule.getWorkflowRef() != null) {
      List<String> refs =
          relationshipService.filter(
              RelationshipType.WORKFLOW,
              Optional.of(List.of(schedule.getWorkflowRef())),
              Optional.of(RelationshipType.TEAM),
              Optional.of(List.of(team)),
              false);
      if (!refs.isEmpty()) {
        schedule.setWorkflowRef(refs.get(0));
        WorkflowScheduleEntity scheduleEntity = internalCreate(team, schedule);
        WorkflowSchedule response = convertScheduleEntityToModel(scheduleEntity);
        return response;
      }
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  public WorkflowScheduleEntity internalCreate(final String team, final WorkflowSchedule schedule) {
    // Validate required fields are present
    if ((WorkflowScheduleType.runOnce.equals(schedule.getType())
            && schedule.getDateSchedule() == null)
        || (!WorkflowScheduleType.runOnce.equals(schedule.getType())
            && schedule.getCronSchedule() == null)
        || schedule.getTimezone() == null
        || schedule.getTimezone().isBlank()) {
      throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REQ);
    }
    Workflow workflow =
        engineClient.getWorkflow(schedule.getWorkflowRef(), Optional.empty(), false);
    WorkflowScheduleEntity scheduleEntity = new WorkflowScheduleEntity();
    BeanUtils.copyProperties(schedule, scheduleEntity, "schedulerRef", "id");
    Boolean enableJob = false;
    if (WorkflowScheduleStatus.active.equals(scheduleEntity.getStatus())
        && workflow != null
        && workflow.getTriggers().getSchedule().getEnabled()) {
      enableJob = true;
    } else if (WorkflowScheduleStatus.active.equals(scheduleEntity.getStatus())
        && workflow != null
        && !workflow.getTriggers().getSchedule().getEnabled()) {
      scheduleEntity.setStatus(WorkflowScheduleStatus.trigger_disabled);
    }
    // Only create JobRunr if Schedule is enabled. As there is no pause functionality in JobRunr.
    if (enableJob) {
      String schedulerRef = createOrUpdateSchedule(team, scheduleEntity);
      scheduleEntity.setSchedulerRef(schedulerRef);
    }
    return scheduleRepository.save(scheduleEntity);
  }

  /*
   * Helper method to convert from Entity to Model as well as adding in the next schedule date.
   *
   * @return the single returnable schedule.
   */
  private WorkflowSchedule convertScheduleEntityToModel(WorkflowScheduleEntity entity) {
    try {
      WorkflowSchedule schedule = new WorkflowSchedule(entity, getNextTriggerDate(entity));
      relationshipService.getSlugByRefForType(RelationshipType.WORKFLOW, schedule.getWorkflowRef());
      schedule.setWorkflowRef(
          relationshipService.getSlugByRefForType(
              RelationshipType.WORKFLOW, schedule.getWorkflowRef()));
      return schedule;
    } catch (Exception e) {
      // Trap exception as we still want to return the dates that we can
      LOGGER.warn("Unable to retrieve next schedule date for {}, skipping.", entity.getId());
      WorkflowSchedule schedule = new WorkflowSchedule(entity);
      relationshipService.getSlugByRefForType(RelationshipType.WORKFLOW, schedule.getWorkflowRef());
      schedule.setWorkflowRef(
          relationshipService.getSlugByRefForType(
              RelationshipType.WORKFLOW, schedule.getWorkflowRef()));
      return schedule;
    }
  }

  /*
   * Retrieves the calendar dates between a start and end date period for the schedules provided.
   *
   * @return list of Schedule Calendars
   *
   * TODO add relationship check
   */
  public List<WorkflowScheduleCalendar> calendars(
      String team, final List<String> scheduleIds, Date fromDate, Date toDate) {
    List<WorkflowScheduleCalendar> scheduleCalendars = new LinkedList<>();
    final Optional<List<WorkflowScheduleEntity>> scheduleEntities =
        scheduleRepository.findByIdInAndStatusIn(scheduleIds, getStatusesNotCompletedOrDeleted());
    if (scheduleEntities.isPresent()) {
      scheduleEntities
          .get()
          .forEach(
              e -> {
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
  public List<WorkflowScheduleCalendar> getCalendarsForWorkflow(
      String team, final String workflowId, Date fromDate, Date toDate) {
    if (relationshipService.hasNodes(
        RelationshipType.TEAM,
        team,
        RelationshipType.WORKFLOW,
        Optional.of(List.of(workflowId)),
        Optional.empty(),
        Optional.empty())) {
      List<WorkflowScheduleCalendar> scheduleCalendars = new LinkedList<>();
      final Optional<List<WorkflowScheduleEntity>> scheduleEntities =
          scheduleRepository.findByWorkflowRefInAndStatusIn(
              List.of(workflowId), getStatusesNotCompletedOrDeleted());
      if (scheduleEntities.isPresent()) {
        scheduleEntities
            .get()
            .forEach(
                e -> {
                  WorkflowScheduleCalendar scheduleCalendar = new WorkflowScheduleCalendar();
                  scheduleCalendar.setScheduleId(e.getId());
                  scheduleCalendar.setDates(getCalendarForDates(e.getId(), fromDate, toDate));
                  scheduleCalendars.add(scheduleCalendar);
                });
      }
      return scheduleCalendars;
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  /*
   * Retrieves the calendar dates between a start and end date period for a specific schedule
   *
   * @return a list of dates for a single Schedule Calendar
   */
  private List<Date> getCalendarForDates(final String scheduleId, Date fromDate, Date toDate) {
    final WorkflowScheduleEntity scheduleEntity =
        scheduleRepository.findById(scheduleId).orElse(null);
    if (scheduleEntity != null) {
      try {
        if (WorkflowScheduleType.runOnce.equals(scheduleEntity.getType())) {
          return List.of(scheduleEntity.getDateSchedule());
        } else {
          return getCronTriggerDates(
              scheduleEntity.getCronSchedule(), fromDate, toDate, scheduleEntity.getTimezone());
        }
      } catch (Exception e) {
        // Trap exception as we still want to return the dates that we can
        e.printStackTrace();
        LOGGER.warn(
            "Unable to retrieve calendar for Schedule: {}, skipping.", scheduleEntity.getId());
      }
    }
    return new LinkedList<>();
  }

  /*
   * Update a schedule based on the payload and the Schedules Id.
   *
   * @return echos the updated schedule
   */
  public WorkflowSchedule apply(String team, final WorkflowSchedule request) {
    if (request != null
        && request.getId() != null
        && !request.getId().isBlank()
        && !request.getId().isEmpty()) {
      final Optional<WorkflowScheduleEntity> optScheduleEntity =
          scheduleRepository.findById(request.getId());
      if (optScheduleEntity.isPresent()) {
        WorkflowScheduleEntity scheduleEntity = optScheduleEntity.get();
        /*
         * The copy ignores ID, workflowRef and creationDate to ensure data integrity
         */
        WorkflowScheduleStatus previousStatus = scheduleEntity.getStatus();
        BeanUtils.copyProperties(
            request, scheduleEntity, "id", "creationDate", "workflowRef", "schedulerRef");

        /*
         * Complex Status checking to determine what can and can't be enabled, incl date in the past check
         */
        WorkflowScheduleStatus newStatus = scheduleEntity.getStatus();
        Workflow workflow =
            workflowService.get(team, request.getWorkflowRef(), Optional.empty(), false);
        Boolean enableJob = true;
        if (!previousStatus.equals(newStatus)) {
          if (WorkflowScheduleStatus.active.equals(previousStatus)
              && WorkflowScheduleStatus.inactive.equals(newStatus)) {
            scheduleEntity.setStatus(WorkflowScheduleStatus.inactive);
            enableJob = false;
          } else if (WorkflowScheduleStatus.inactive.equals(previousStatus)
              && WorkflowScheduleStatus.active.equals(newStatus)) {
            if (workflow != null && !workflow.getTriggers().getSchedule().getEnabled()) {
              scheduleEntity.setStatus(WorkflowScheduleStatus.trigger_disabled);
              enableJob = false;
            }
            if (WorkflowScheduleType.runOnce.equals(scheduleEntity.getType())) {
              Date currentDate = new Date();
              if (scheduleEntity.getDateSchedule().getTime() < currentDate.getTime()) {
                scheduleEntity.setStatus(WorkflowScheduleStatus.error);
                scheduleRepository.save(scheduleEntity);
                cancelJob(scheduleEntity.getSchedulerRef());
                LOGGER.error(
                    "Cannot enable schedule ({}) as it is in the past.", scheduleEntity.getId());
                throw new BoomerangException(
                    BoomerangError.SCHEDULE_INVALID_RUNONCE, scheduleEntity.getId());
              }
            }
          }
        } else {
          if (WorkflowScheduleStatus.inactive.equals(newStatus)) {
            enableJob = false;
          }
        }
        scheduleRepository.save(scheduleEntity);
        if (enableJob) {
          createOrUpdateSchedule(team, scheduleEntity);
        }
        return convertScheduleEntityToModel(scheduleEntity);
      }
    } else if (request != null) {
      request.setId(null);
      return this.create(team, request);
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
  }

  /*
   * Helper method to determine if we are updating a cron or runonce schedule. It also handles
   * pausing a schedule if the status is set to pause.
   */
  private String createOrUpdateSchedule(final String team, final WorkflowScheduleEntity schedule) {
    if (WorkflowScheduleType.runOnce.equals(schedule.getType())) {
      return createOrUpdateRunOnceJob(team, schedule);
    } else {
      return createOrUpdateCronJob(team, schedule);
    }
  }

  /*
   * Enables all schedules that have been disabled by the trigger being disabled. This is needed to
   * differentiate between user paused and trigger disabled schedules.
   */
  protected void enableAllTriggerSchedules(final String team, final String workflowId) {
    final Optional<List<WorkflowScheduleEntity>> entities =
        scheduleRepository.findByWorkflowRefInAndStatusIn(
            List.of(workflowId), List.of(WorkflowScheduleStatus.trigger_disabled));
    if (entities.isPresent()) {
      entities
          .get()
          .forEach(
              s -> {
                try {
                  enableSchedule(team, s.getId());
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
  private void enableSchedule(final String team, final String scheduleId)
      throws SchedulerException {
    Optional<WorkflowScheduleEntity> optSchedule = scheduleRepository.findById(scheduleId);
    if (optSchedule.isPresent()) {
      WorkflowScheduleEntity scheduleEntity = optSchedule.get();
      if (WorkflowScheduleType.runOnce.equals(scheduleEntity.getType())) {
        Date currentDate = new Date();
        if (scheduleEntity.getDateSchedule().getTime() < currentDate.getTime()) {
          LOGGER.error("Cannot enable schedule ({}) as it is in the past.", scheduleEntity.getId());
          scheduleEntity.setStatus(WorkflowScheduleStatus.error);
          cancelJob(scheduleEntity.getSchedulerRef());
          scheduleRepository.save(scheduleEntity);
        }
      }
      scheduleEntity.setStatus(WorkflowScheduleStatus.active);
      scheduleRepository.save(scheduleEntity);
      this.createOrUpdateSchedule(team, scheduleEntity);
    }
  }

  /*
   * Disables all schedules that are currently active and is used when the trigger is disabled.
   */
  protected void disableAllTriggerSchedules(final String team, final String workflowId) {
    final Optional<List<WorkflowScheduleEntity>> entities =
        scheduleRepository.findByWorkflowRefInAndStatusIn(
            List.of(workflowId), List.of(WorkflowScheduleStatus.active));
    if (entities.isPresent()) {
      entities
          .get()
          .forEach(
              s -> {
                s.setStatus(WorkflowScheduleStatus.trigger_disabled);
                scheduleRepository.save(s);
                this.cancelJob(s.getSchedulerRef());
              });
    }
  }

  /*
   * Complete a specific schedule
   *
   * Used by ExecuteScheduleJob
   */
  public void complete(String scheduleId) {
    Optional<WorkflowScheduleEntity> schedule = scheduleRepository.findById(scheduleId);
    if (schedule.isPresent()) {
      schedule.get().setStatus(WorkflowScheduleStatus.completed);
      scheduleRepository.save(schedule.get());
    }
  }

  /*
   * Mark all schedules as deleted and cancel the quartz jobs. This is used when a workflow is deleted.
   */
  protected void deleteAllForWorkflow(final String workflowId) {
    final Optional<List<WorkflowScheduleEntity>> entities =
        scheduleRepository.findByWorkflowRef(workflowId);
    if (entities.isPresent()) {
      entities
          .get()
          .forEach(
              s -> {
                this.internalDelete(s);
              });
    }
  }

  /*
   * Mark a single schedule as deleted and cancel the quartz jobs. Used by the UI when deleting a schedule.
   */
  public void delete(String team, final String scheduleId) {
    final Optional<WorkflowScheduleEntity> schedule = scheduleRepository.findById(scheduleId);
    if (schedule.isPresent()
        && relationshipService.hasNodes(
            RelationshipType.TEAM,
            team,
            RelationshipType.WORKFLOW,
            Optional.of(List.of(schedule.get().getWorkflowRef())),
            Optional.empty(),
            Optional.empty())) {
      this.internalDelete(schedule.get());
    } else {
      throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REF);
    }
  }

  private void internalDelete(WorkflowScheduleEntity entity) {
    LOGGER.debug("Deleting schedule: {}", entity.getId());
    cancelJob(entity.getId());
    scheduleRepository.deleteById(entity.getId());
  }

  private List<WorkflowScheduleStatus> getStatusesNotCompletedOrDeleted() {
    List<WorkflowScheduleStatus> statuses = new LinkedList<>();
    statuses.add(WorkflowScheduleStatus.active);
    statuses.add(WorkflowScheduleStatus.inactive);
    statuses.add(WorkflowScheduleStatus.trigger_disabled);
    statuses.add(WorkflowScheduleStatus.error);
    return statuses;
  }

  private String createOrUpdateCronJob(String team, WorkflowScheduleEntity scheduleEntity) {
    String cronSchedule = scheduleEntity.getCronSchedule();
    String timezone = scheduleEntity.getTimezone();
    if (cronSchedule != null && timezone != null) {
      CronExpression cronExpression;
      try {
        cronExpression = CronExpression.create(cronSchedule);
      } catch (Exception e) {
        LOGGER.error("Error validating / creating CRON expression: {}", cronSchedule, e);
        throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_CRON);
      }

      TimeZone timeZone = TimeZone.getTimeZone(timezone);
      try {
        if (scheduleEntity.getSchedulerRef() != null
            && !scheduleEntity.getSchedulerRef().isBlank()) {
          LOGGER.debug(
              "Cancelling existing Recurring Schedule: {}", scheduleEntity.getSchedulerRef());
          jobScheduler.deleteRecurringJob(scheduleEntity.getSchedulerRef());
        }
        return jobScheduler.scheduleRecurrently(
            scheduleEntity.getId(),
            cronExpression.getExpression(),
            timeZone.toZoneId(),
            () -> job.execute(team, scheduleEntity.getWorkflowRef(), scheduleEntity.getId()));
      } catch (Exception e) {
        LOGGER.error("Unable to create Recurring Schedule. {}", ExceptionUtils.getStackTrace(e));
        throw new BoomerangException(BoomerangError.SCHEDULE_ERROR, e.getMessage());
      }
    }

    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REQ);
  }

  public String createOrUpdateRunOnceJob(String team, WorkflowScheduleEntity scheduleEntity) {
    String timezone = scheduleEntity.getTimezone();
    Date dateSchedule = scheduleEntity.getDateSchedule();
    if (dateSchedule != null && timezone != null) {
      TimeZone timeZone = TimeZone.getTimeZone(timezone);
      String scheduleId = scheduleEntity.getId();
      try {
        if (scheduleEntity.getSchedulerRef() != null
            && !scheduleEntity.getSchedulerRef().isBlank()) {
          try {
            LOGGER.debug(
                "Cancelling existing RunOnce Schedule: {}", scheduleEntity.getSchedulerRef());
            cancelJob(scheduleEntity.getSchedulerRef());
          } catch (Exception e) {
            LOGGER.warn(
                "Error cancelling existing RunOnce Schedule: {}, {}",
                scheduleEntity.getSchedulerRef(),
                e.getMessage());
          }
        }
        JobId jobId =
            jobScheduler.schedule(
                dateSchedule.toInstant(),
                () -> job.execute(team, scheduleEntity.getWorkflowRef(), scheduleId));
        LOGGER.info(jobId);
        return jobId.toString();
      } catch (Exception e) {
        LOGGER.error("Unable to create RunOnce Schedule. {}", e.getMessage());
      }
    }
    throw new BoomerangException(BoomerangError.SCHEDULE_INVALID_REQ);
  }

  private void cancelJob(String schedulerRef) {
    jobScheduler.delete(JobId.parse(schedulerRef));
  }

  /**
   * Retrieve the list of dates that a cron expression will trigger between two dates.
   *
   * @param cronExpression
   * @param fromDate
   * @param toDate
   * @param timezone
   * @return
   */
  private List<Date> getCronTriggerDates(
      String cronExpression, Date fromDate, Date toDate, String timezone) {
    List<Date> triggerDates = new LinkedList<>();

    if (cronExpression != null && timezone != null) {
      try {
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        CronParser parser = new CronParser(cronDefinition);
        Cron cron = parser.parse(cronExpression);
        ExecutionTime executionTime = ExecutionTime.forCron(cron);

        ZoneId zoneId = TimeZone.getTimeZone(timezone).toZoneId();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime startTime = fromDate.toInstant().atZone(zoneId);
        ZonedDateTime endTime = toDate.toInstant().atZone(zoneId);

        // Ensure startTime is not in the past
        if (startTime.isBefore(now)) {
          startTime = now;
        }

        List<ZonedDateTime> executionDates = executionTime.getExecutionDates(startTime, endTime);
        executionDates.stream().forEach(d -> triggerDates.add(Date.from(d.toInstant())));
      } catch (Exception e) {
        LOGGER.error("Error getting cron trigger dates for expression: {}", cronExpression, e);
      }
    }

    return triggerDates;
  }

  /**
   * Retrieve the next trigger date for a given schedule based on its cron expression and timezone.
   */
  private Date getNextTriggerDate(WorkflowScheduleEntity schedule) {
    String cronString = schedule.getCronSchedule();
    String timezone = schedule.getTimezone();
    ZonedDateTime now = ZonedDateTime.now();
    if (cronString != null && timezone != null) {
      try {
        CronExpression cronExpression = CronExpression.create(cronString);
        Instant next =
            cronExpression.next(
                schedule.getCreationDate().toInstant(),
                ZonedDateTime.now().toInstant(),
                TimeZone.getTimeZone(timezone).toZoneId());
        if (next != null) {
          return Date.from(next);
        }
      } catch (Exception e) {
        LOGGER.error(
            "Unable to retrieve next trigger date for Schedule: {}, skipping.", schedule.getId());
        LOGGER.error(ExceptionUtils.getStackTrace(e));
      }
    }
    return null;
  }
}
