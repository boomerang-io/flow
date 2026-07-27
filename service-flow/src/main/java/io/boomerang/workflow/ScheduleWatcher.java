package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workflow.repository.WorkflowScheduleRepository;
import java.time.ZonedDateTime;
import java.util.Date;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Level-triggered schedule firing that runs on every instance - no leader election. Two paged
 * sweeps: initialise legacy schedules that carry no nextFireAt, then fire schedules whose
 * nextFireAt has passed. Firing advances nextFireAt in one Compare-And-Set, so exactly one instance
 * fires per tick; a crash after the advance loses that single fire, never duplicates it. Startup
 * jitter de-phases the instances' schedules.
 */
@Service
public class ScheduleWatcher {

  private static final Logger LOGGER = LogManager.getLogger();
  private static final int PAGE_SIZE = 50;

  private final WorkflowScheduleRepository scheduleRepository;
  private final ScheduleService scheduleService;
  private final ScheduleJob scheduleJob;
  private final RelationshipService relationshipService;

  @Value("${flow.schedule.watcher.enabled:true}")
  private boolean enabled;

  public ScheduleWatcher(
      WorkflowScheduleRepository scheduleRepository,
      ScheduleService scheduleService,
      ScheduleJob scheduleJob,
      RelationshipService relationshipService) {
    this.scheduleRepository = scheduleRepository;
    this.scheduleService = scheduleService;
    this.scheduleJob = scheduleJob;
    this.relationshipService = relationshipService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (enabled) {
      sweep();
    }
  }

  @Scheduled(
      initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(30000)}",
      fixedDelayString = "${flow.schedule.watcher.interval-ms:30000}")
  public void sweep() {
    initializeSchedules();
    fireDueSchedules();
  }

  /**
   * Bootstrap active cron schedules created under the legacy model that carry no nextFireAt:
   * compute and set it WITHOUT firing. A run-once schedule always carries its nextFireAt from
   * creation, so it is skipped here.
   */
  public void initializeSchedules() {
    for (WorkflowScheduleEntity schedule :
        scheduleRepository.findByStatusAndNextFireAtIsNull(
            WorkflowScheduleStatus.active, PageRequest.of(0, PAGE_SIZE))) {
      try {
        if (WorkflowScheduleType.runOnce.equals(schedule.getType())) {
          continue;
        }
        Date next =
            scheduleService.nextOccurrence(
                schedule.getCronSchedule(), schedule.getTimezone(), ZonedDateTime.now());
        if (next != null) {
          scheduleService.initializeNextFireAt(schedule.getId(), next);
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Schedule initialise failed: {}", schedule.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Fire active schedules whose nextFireAt has passed. Each fire is one advance-Compare-And-Set;
   * only the winner submits the run. The next occurrence is computed from NOW, not the stale
   * nextFireAt, so a backlog collapses to a single fire - never a catch-up storm.
   */
  public void fireDueSchedules() {
    Date now = new Date();
    for (WorkflowScheduleEntity schedule :
        scheduleRepository.findByStatusAndNextFireAtLessThanEqual(
            WorkflowScheduleStatus.active, now, PageRequest.of(0, PAGE_SIZE))) {
      try {
        Date next =
            WorkflowScheduleType.runOnce.equals(schedule.getType())
                ? null
                : scheduleService.nextOccurrence(
                    schedule.getCronSchedule(), schedule.getTimezone(), ZonedDateTime.now());
        if (scheduleService.tryClaimFire(schedule.getId(), schedule.getNextFireAt(), next, now)) {
          LOGGER.info(
              "[{}] Schedule fired for Workflow ({}).",
              schedule.getId(),
              schedule.getWorkflowRef());
          scheduleJob.execute(resolveTeam(schedule), schedule.getWorkflowRef(), schedule.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Schedule fire failed: {}", schedule.getId(), ex.getMessage());
      }
    }
  }

  // The owning team is the workflow's parent in the relationship graph - the source of truth,
  // always current (a denormalized copy could go stale if the workflow moved teams).
  private String resolveTeam(WorkflowScheduleEntity schedule) {
    return relationshipService.getParentByLabel(
        RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, schedule.getWorkflowRef());
  }
}
