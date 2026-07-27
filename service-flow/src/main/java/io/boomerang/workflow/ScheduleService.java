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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/*
 * Workflow Schedule Service provides all the methods for both the Schedules page and the individual
 * Workflow Schedule. Firing is level-triggered: schedules carry a nextFireAt that the
 * ScheduleWatcher sweep advances-and-fires via a Compare-And-Set - no external job scheduler.
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

  public ScheduleService(
      WorkflowScheduleRepository scheduleRepository,
      WorkflowService workflowService,
      RelationshipService relationshipService,
      EngineClient engineClient,
      MongoTemplate mongoTemplate) {
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
      createOrUpdateSchedule(scheduleEntity);
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
          createOrUpdateSchedule(scheduleEntity);
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
  private void createOrUpdateSchedule(final WorkflowScheduleEntity schedule) {
    schedule.setNextFireAt(computeNextFireAt(schedule, ZonedDateTime.now()));
    scheduleRepository.save(schedule);
  }

  /**
   * The next fire time for a schedule: the run-once date, or the next cron occurrence from
   * {@code from}. Returns null when there is no future occurrence.
   */
  private Date computeNextFireAt(WorkflowScheduleEntity schedule, ZonedDateTime from) {
    if (WorkflowScheduleType.runOnce.equals(schedule.getType())) {
      return schedule.getDateSchedule();
    }
    return nextOccurrence(schedule.getCronSchedule(), schedule.getTimezone(), from);
  }

  /**
   * The next occurrence of a cron expression at or after {@code from}, using cron-utils - the same
   * parser as the forward calendar, so firing and preview never disagree. Null on a bad
   * expression or no future occurrence.
   */
  public Date nextOccurrence(String cron, String timezone, ZonedDateTime from) {
    if (cron == null || timezone == null) {
      return null;
    }
    try {
      CronDefinition definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
      ExecutionTime executionTime = ExecutionTime.forCron(new CronParser(definition).parse(cron));
      ZoneId zone = TimeZone.getTimeZone(timezone).toZoneId();
      return executionTime
          .nextExecution(from.withZoneSameInstant(zone))
          .map(next -> Date.from(next.toInstant()))
          .orElse(null);
    } catch (Exception e) {
      LOGGER.error("Unable to compute next cron occurrence for expression: {}", cron, e);
      return null;
    }
  }

  /**
   * Fire-claim Compare-And-Set: advance {@code nextFireAt} from its observed value to the next
   * occurrence in one atomic write. The query pinning the observed {@code nextFireAt} is the
   * fence - only one instance wins per tick; a racing instance's query misses because the value
   * already advanced. Returns whether this instance won the fire.
   */
  public boolean tryClaimFire(
      String id, Date observedNextFireAt, Date newNextFireAt, Date now) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(WorkflowScheduleStatus.active)
                .and("nextFireAt")
                .is(observedNextFireAt));
    Update update = new Update().set("nextFireAt", newNextFireAt).set("lastFiredAt", now);
    return mongoTemplate.updateFirst(query, update, WorkflowScheduleEntity.class).getModifiedCount()
        > 0;
  }

  /**
   * Bootstrap a legacy schedule that carries no next fire time: set {@code nextFireAt} without
   * firing. Guarded on {@code nextFireAt} still absent so concurrent sweeps do not double-initialise.
   */
  public void initializeNextFireAt(String id, Date nextFireAt) {
    Query query = Query.query(Criteria.where("_id").is(id).and("nextFireAt").exists(false));
    Update update = new Update().set("nextFireAt", nextFireAt);
    mongoTemplate.updateFirst(query, update, WorkflowScheduleEntity.class);
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
      entities.get().forEach(s -> enableSchedule(team, s.getId()));
    }
  }

  /*
   * Enables a specific schedule
   */
  private void enableSchedule(final String team, final String scheduleId) {
    Optional<WorkflowScheduleEntity> optSchedule = scheduleRepository.findById(scheduleId);
    if (optSchedule.isPresent()) {
      WorkflowScheduleEntity scheduleEntity = optSchedule.get();
      if (WorkflowScheduleType.runOnce.equals(scheduleEntity.getType())) {
        Date currentDate = new Date();
        if (scheduleEntity.getDateSchedule().getTime() < currentDate.getTime()) {
          LOGGER.error("Cannot enable schedule ({}) as it is in the past.", scheduleEntity.getId());
          scheduleEntity.setStatus(WorkflowScheduleStatus.error);
          scheduleRepository.save(scheduleEntity);
        }
      }
      scheduleEntity.setStatus(WorkflowScheduleStatus.active);
      scheduleRepository.save(scheduleEntity);
      this.createOrUpdateSchedule(scheduleEntity);
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
                // Status alone stops firing - the sweep only fires active schedules.
                s.setStatus(WorkflowScheduleStatus.trigger_disabled);
                scheduleRepository.save(s);
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
   * Mark all schedules as deleted and cancel the scheduled jobs. This is used when a workflow is deleted.
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
   * Mark a single schedule as deleted and cancel the scheduled jobs. Used by the UI when deleting a schedule.
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
    return nextOccurrence(
        schedule.getCronSchedule(), schedule.getTimezone(), ZonedDateTime.now());
  }
}
