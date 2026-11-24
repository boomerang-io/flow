package io.boomerang.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.TokenService;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.WorkflowService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the level-triggered schedule firing against a real MongoDB (Testcontainers): the
 * advance-Compare-And-Set exactly-once fence, misfire collapse, legacy initialisation, and the
 * ScheduleWatcher sweep with a mocked fire action. Wires only the WorkflowScheduleRepository +
 * MongoTemplate; ScheduleService is constructed directly with mocked collaborators.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ScheduleWatcherTest.MongoTestConfig.class)
@TestPropertySource(properties = "flow.mongo.collection.prefix=flowtest")
class ScheduleWatcherTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    MONGO.start();
  }

  private static final String HOURLY = "0 * * * *"; // UNIX cron: minute 0 of every hour

  @Configuration
  @EnableMongoRepositories(
      basePackageClasses = WorkflowScheduleRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = WorkflowScheduleRepository.class))
  static class MongoTestConfig {

    @Bean
    static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
      return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    MongoConfiguration mongoConfiguration() {
      return new MongoConfiguration();
    }

    @Bean
    MongoDatabaseFactory mongoDatabaseFactory() {
      return new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl("boomerang"));
    }

    @Bean
    MongoMappingContext mongoMappingContext() {
      return new MongoMappingContext();
    }

    @Bean
    MappingMongoConverter mappingMongoConverter(
        MongoDatabaseFactory mongoDatabaseFactory, MongoMappingContext mongoMappingContext) {
      return new MappingMongoConverter(
          new DefaultDbRefResolver(mongoDatabaseFactory), mongoMappingContext);
    }

    @Bean
    MongoTemplate mongoTemplate(
        MongoDatabaseFactory mongoDatabaseFactory, MappingMongoConverter mappingMongoConverter) {
      return new MongoTemplate(mongoDatabaseFactory, mappingMongoConverter);
    }
  }

  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private MongoTemplate mongoTemplate;

  private ScheduleService service;
  private WorkflowService workflowService;
  private ScheduleJob scheduleJob;
  private RelationshipService relationshipService;
  private ScheduleWatcher watcher;

  @BeforeEach
  void setUp() {
    scheduleRepository.deleteAll();
    relationshipService = mock(RelationshipService.class);
    workflowService = mock(WorkflowService.class);
    service =
        new ScheduleService(
            scheduleRepository,
            workflowService,
            relationshipService,
            mongoTemplate,
            new CronService());
    scheduleJob = mock(ScheduleJob.class);
    watcher = new ScheduleWatcher(scheduleRepository, service, scheduleJob, relationshipService);
  }

  @AfterEach
  void clearSchedulerIdentity() {
    // The real ScheduleJob leaves the scheduler's token on the thread.
    SecurityContextHolder.clearContext();
  }

  private WorkflowScheduleEntity activeRunOnce(Date at) {
    WorkflowScheduleEntity s = new WorkflowScheduleEntity();
    s.setWorkflowRef("w1");
    s.setType(WorkflowScheduleType.runOnce);
    s.setStatus(WorkflowScheduleStatus.active);
    s.setDateSchedule(at);
    s.setTimezone("UTC");
    s.setNextFireAt(at);
    return scheduleRepository.save(s);
  }

  // The watcher with the real ScheduleJob, so an accepted run-once fire completes its schedule;
  // only the submit itself is mocked.
  private ScheduleWatcher watcherSubmittingThrough(ScheduleService scheduleService) {
    when(relationshipService.getParentByLabel(any(), any(), eq("w1"))).thenReturn("t1");
    return new ScheduleWatcher(
        scheduleRepository,
        scheduleService,
        new ScheduleJob(
            workflowService, scheduleService, mock(TokenService.class), relationshipService),
        relationshipService);
  }

  // Both sweeps, as the scheduled sweep runs them; its switch is off in a hand-built watcher.
  private static void sweep(ScheduleWatcher instance) {
    instance.initializeSchedules();
    instance.fireDueSchedules();
  }

  private void refuseEverySubmit() {
    when(workflowService.submit(any(), any(), any(), eq(true), any()))
        .thenThrow(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, "Number of runs", 1, 1));
  }

  private WorkflowScheduleEntity activeCron(Date nextFireAt) {
    WorkflowScheduleEntity s = new WorkflowScheduleEntity();
    s.setWorkflowRef("w1");
    s.setType(WorkflowScheduleType.cron);
    s.setStatus(WorkflowScheduleStatus.active);
    s.setCronSchedule(HOURLY);
    s.setTimezone("UTC");
    s.setNextFireAt(nextFireAt);
    return scheduleRepository.save(s);
  }

  @Test
  void tryClaimFireIsExactlyOnce() {
    Date due = new Date(System.currentTimeMillis() - 1000);
    WorkflowScheduleEntity s = activeCron(due);
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    Date now = new Date();

    // Two instances race the same observed nextFireAt: exactly one wins the advance.
    boolean a = service.tryClaimFire(s.getId(), due, next, now);
    boolean b = service.tryClaimFire(s.getId(), due, next, now);
    assertTrue(a ^ b, "exactly one instance must win the fire");

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(next, after.getNextFireAt(), "nextFireAt advanced exactly once");
    assertNotNull(after.getLastFiredAt());
  }

  @Test
  void misfireCollapsesToNextFutureOccurrence() {
    Date longAgo = new Date(System.currentTimeMillis() - 86400000L * 30);
    WorkflowScheduleEntity s = activeCron(longAgo);
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    service.tryClaimFire(s.getId(), longAgo, next, new Date());
    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertTrue(
        after.getNextFireAt().after(new Date()),
        "a backlog collapses to the next future fire, never a catch-up storm");
  }

  @Test
  void initializeNextFireAtSetsWithoutFiring() {
    WorkflowScheduleEntity s = activeCron(null);
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    service.initializeNextFireAt(s.getId(), next);

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(next, after.getNextFireAt());
    assertNull(after.getLastFiredAt(), "initialise must not fire");

    // Guarded on nextFireAt absent: a second initialise does not overwrite.
    service.initializeNextFireAt(s.getId(), new Date(0));
    assertEquals(next, scheduleRepository.findById(s.getId()).orElseThrow().getNextFireAt());
  }

  @Test
  void initializesSchedulesStoredInEveryEarlierCronForm() {
    // v3/v4 stored Quartz (seconds first, "?"); the schedule form later saved "0 mm HH * DAYS".
    // Neither parses as UNIX, so both must be converted before the next fire can be computed.
    WorkflowScheduleEntity quartz = activeCron(null);
    quartz.setCronSchedule("0 30 9 ? * MON-FRI");
    scheduleRepository.save(quartz);
    WorkflowScheduleEntity formOrder = activeCron(null);
    formOrder.setCronSchedule("0 30 09 * MON,TUE,WED,THU,FRI");
    scheduleRepository.save(formOrder);

    watcher.initializeSchedules();

    for (WorkflowScheduleEntity s : new WorkflowScheduleEntity[] {quartz, formOrder}) {
      Date next = scheduleRepository.findById(s.getId()).orElseThrow().getNextFireAt();
      assertNotNull(next, s.getCronSchedule() + " must get a next fire time");
      ZonedDateTime at = next.toInstant().atZone(java.time.ZoneOffset.UTC);
      assertEquals(9, at.getHour());
      assertEquals(30, at.getMinute());
      assertTrue(at.getDayOfWeek().getValue() <= 5, "a weekday");
    }
  }

  @Test
  void nextOccurrenceIsFutureAndTolerantOfBadCron() {
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    assertNotNull(next);
    assertTrue(next.after(new Date()));
    assertNull(
        service.nextOccurrence("not-a-cron", "UTC", ZonedDateTime.now()),
        "a bad expression returns null, never throws");
  }

  @Test
  void watcherFiresDueScheduleOnceThenAdvancesPastDue() {
    Date due = new Date(System.currentTimeMillis() - 1000);
    WorkflowScheduleEntity s = activeCron(due);
    // The owning team is resolved from the relationship graph at fire time.
    when(relationshipService.getParentByLabel(any(), any(), eq("w1"))).thenReturn("t1");

    watcher.fireDueSchedules();

    verify(scheduleJob, times(1)).execute(eq("t1"), eq("w1"), eq(s.getId()));
    assertTrue(
        scheduleRepository.findById(s.getId()).orElseThrow().getNextFireAt().after(new Date()),
        "the fired schedule's nextFireAt is now in the future");

    // A second sweep does not re-fire: nextFireAt is no longer due.
    watcher.fireDueSchedules();
    verify(scheduleJob, times(1)).execute(any(), any(), any());
  }

  @Test
  void watcherInitializesLegacyScheduleWithoutFiring() {
    WorkflowScheduleEntity s = activeCron(null);

    watcher.initializeSchedules();

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertNotNull(after.getNextFireAt(), "legacy schedule gets a nextFireAt");
    verify(scheduleJob, never()).execute(any(), any(), any());
  }

  @Test
  void failedFireRetriesWithBackoffThenSkipsAtMaxAttempts() {
    WorkflowScheduleEntity s = activeCron(new Date(System.currentTimeMillis() - 1000));
    when(relationshipService.getParentByLabel(any(), any(), eq("w1"))).thenReturn("t1");
    doThrow(new RuntimeException("submit boom")).when(scheduleJob).execute(any(), any(), any());

    // Attempt 1: re-armed for retry - counter incremented, nextFireAt pulled back to soon.
    watcher.fireDueSchedules();
    WorkflowScheduleEntity after1 = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(1, after1.getRetryCount());
    assertTrue(after1.getNextFireAt().after(new Date()), "re-armed to a future retry time");
    assertTrue(
        after1.getNextFireAt().before(new Date(System.currentTimeMillis() + 30000)),
        "retry is soon (backoff), not the next hourly occurrence");

    // Attempt 2: still under the cap, re-armed again.
    makeDue(s.getId());
    watcher.fireDueSchedules();
    assertEquals(2, scheduleRepository.findById(s.getId()).orElseThrow().getRetryCount());

    // Attempt 3 == MAX: counter clears and the occurrence is skipped (no further re-arm).
    makeDue(s.getId());
    watcher.fireDueSchedules();
    assertEquals(
        0,
        scheduleRepository.findById(s.getId()).orElseThrow().getRetryCount(),
        "attempts exhausted - counter cleared, occurrence skipped");
  }

  @Test
  void successfulFireClearsRetryCount() {
    WorkflowScheduleEntity s = activeCron(new Date(System.currentTimeMillis() - 1000));
    s.setRetryCount(2);
    scheduleRepository.save(s);
    when(relationshipService.getParentByLabel(any(), any(), eq("w1"))).thenReturn("t1");

    watcher.fireDueSchedules();

    assertEquals(
        0,
        scheduleRepository.findById(s.getId()).orElseThrow().getRetryCount(),
        "a successful fire resets the attempt counter");
  }

  @Test
  void aRunOnceRefusedOnEveryAttemptEndsInErrorAndNeverFiresAgain() {
    WorkflowScheduleEntity s = activeRunOnce(new Date(System.currentTimeMillis() - 1000));
    ScheduleWatcher refused = watcherSubmittingThrough(service);
    refuseEverySubmit();

    // Attempts 1 and 2 re-arm the schedule: still active, due again after the backoff.
    for (int attempt = 1; attempt <= 2; attempt++) {
      sweep(refused);
      WorkflowScheduleEntity rearmed = scheduleRepository.findById(s.getId()).orElseThrow();
      assertEquals(WorkflowScheduleStatus.active, rearmed.getStatus());
      assertEquals(attempt, rearmed.getRetryCount());
      assertTrue(rearmed.getNextFireAt().after(new Date()), "re-armed to a future retry time");
      makeDue(s.getId());
    }

    // Attempt 3 is the last: nothing is left to fire, so the schedule is marked.
    sweep(refused);
    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.error, after.getStatus());
    assertNull(after.getNextFireAt());
    assertEquals(0, after.getRetryCount());

    // Further sweeps, the initialise sweep included, leave it alone.
    sweep(refused);
    sweep(refused);
    verify(workflowService, times(3)).submit(any(), any(), any(), eq(true), any());
    assertEquals(
        WorkflowScheduleStatus.error,
        scheduleRepository.findById(s.getId()).orElseThrow().getStatus());
  }

  @Test
  void aRunOnceRefusedOnceThenAcceptedEndsCompleted() {
    WorkflowScheduleEntity s = activeRunOnce(new Date(System.currentTimeMillis() - 1000));
    ScheduleWatcher accepting = watcherSubmittingThrough(service);
    when(workflowService.submit(any(), any(), any(), eq(true), any()))
        .thenThrow(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, "Number of runs", 1, 1))
        .thenReturn(null);

    sweep(accepting);
    assertEquals(
        WorkflowScheduleStatus.active,
        scheduleRepository.findById(s.getId()).orElseThrow().getStatus());
    makeDue(s.getId());
    sweep(accepting);

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.completed, after.getStatus());
    assertEquals(0, after.getRetryCount());
    sweep(accepting);
    verify(workflowService, times(2)).submit(any(), any(), any(), eq(true), any());
  }

  @Test
  void aRecurringScheduleRefusedOnEveryAttemptStaysActiveForItsNextOccurrence() {
    WorkflowScheduleEntity s = activeCron(new Date(System.currentTimeMillis() - 1000));
    ScheduleWatcher refused = watcherSubmittingThrough(service);
    refuseEverySubmit();

    sweep(refused);
    makeDue(s.getId());
    sweep(refused);
    makeDue(s.getId());
    sweep(refused);

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.active, after.getStatus());
    assertEquals(0, after.getRetryCount());
    // The next hourly occurrence, not a retry: an occurrence falls on the hour, a retry backoff
    // lands at an arbitrary second. Comparing with "now + backoff" failed within 45 s of the hour,
    // when the next occurrence is itself that close.
    assertTrue(after.getNextFireAt().after(new Date()), "the next occurrence is in the future");
    assertEquals(
        0,
        after.getNextFireAt().getTime() % 3_600_000L,
        "the next hourly occurrence stands, not a retry backoff");
    verify(workflowService, times(3)).submit(any(), any(), any(), eq(true), any());
  }

  @Test
  void twoInstancesRacingTheLastAttemptSubmitOnceAndMarkOnce() throws Exception {
    WorkflowScheduleEntity s = activeRunOnce(new Date(System.currentTimeMillis() - 1000));
    s.setRetryCount(2);
    scheduleRepository.save(s);
    // Both instances have read the due page before either claims the fire.
    CyclicBarrier bothRead = new CyclicBarrier(2);
    ScheduleService racing =
        new ScheduleService(
            scheduleRepository, workflowService, relationshipService, mongoTemplate, new CronService()) {
          @Override
          public boolean tryClaimFire(String id, Date observed, Date next, Date now) {
            try {
              bothRead.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return super.tryClaimFire(id, observed, next, now);
          }
        };
    ScheduleWatcher first = watcherSubmittingThrough(racing);
    ScheduleWatcher second = watcherSubmittingThrough(racing);
    refuseEverySubmit();

    ExecutorService instances = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> sweeps =
          List.of(
              instances.submit(first::fireDueSchedules), instances.submit(second::fireDueSchedules));
      for (Future<?> sweep : sweeps) {
        sweep.get(30, TimeUnit.SECONDS);
      }
    } finally {
      instances.shutdownNow();
    }

    verify(workflowService, times(1)).submit(any(), any(), any(), eq(true), any());
    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.error, after.getStatus());
    assertEquals(0, after.getRetryCount());
    assertTrue(!service.failRunOnce(s.getId()), "already marked: a second mark changes nothing");
  }

  @Test
  void aScheduleChangedSinceTheFireIsNotMarked() {
    // Saved with a new date while the last attempt was in flight: the edit stands.
    Date tomorrow = new Date(System.currentTimeMillis() + 86400000L);
    WorkflowScheduleEntity rearmed = activeRunOnce(tomorrow);
    WorkflowScheduleEntity disabled = activeRunOnce(null);
    disabled.setStatus(WorkflowScheduleStatus.inactive);
    scheduleRepository.save(disabled);
    WorkflowScheduleEntity recurring = activeCron(null);

    assertTrue(!service.failRunOnce(rearmed.getId()));
    assertTrue(!service.failRunOnce(disabled.getId()));
    assertTrue(!service.failRunOnce(recurring.getId()));

    assertEquals(
        WorkflowScheduleStatus.active,
        scheduleRepository.findById(rearmed.getId()).orElseThrow().getStatus());
    assertEquals(
        WorkflowScheduleStatus.inactive,
        scheduleRepository.findById(disabled.getId()).orElseThrow().getStatus());
    assertEquals(
        WorkflowScheduleStatus.active,
        scheduleRepository.findById(recurring.getId()).orElseThrow().getStatus());
  }

  @Test
  void aScheduleInErrorAfterARefusedFireIsReArmedBySavingItWithANewDate() {
    WorkflowScheduleEntity s = activeRunOnce(null);
    assertTrue(service.failRunOnce(s.getId()));
    when(relationshipService.hasNodes(any(), any(), any(), any(), any(), any())).thenReturn(true);

    // What the schedule editor sends: the new date and no status, which reads as active.
    Date tomorrow = new Date(System.currentTimeMillis() + 86400000L);
    WorkflowSchedule edit = new WorkflowSchedule();
    edit.setId(s.getId());
    edit.setName("run once");
    edit.setType(WorkflowScheduleType.runOnce);
    edit.setTimezone("UTC");
    edit.setDateSchedule(tomorrow);
    edit.setWorkflowRef("w1");
    service.apply("t1", edit);

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.active, after.getStatus());
    assertEquals(tomorrow, after.getNextFireAt());

    // And it fires at the new date like any other run-once schedule.
    makeDue(s.getId());
    sweep(watcherSubmittingThrough(service));
    assertEquals(
        WorkflowScheduleStatus.completed,
        scheduleRepository.findById(s.getId()).orElseThrow().getStatus());
  }

  @Test
  void aRecurringFireDroppedOnItsLastAttemptIsRecordedWithWhatRefusedIt() {
    WorkflowScheduleEntity s = activeCron(new Date(System.currentTimeMillis() - 1000));
    s.setName("nightly");
    scheduleRepository.save(s);
    ScheduleWatcher refused = watcherSubmittingThrough(service);
    when(workflowService.submit(any(), any(), any(), eq(true), any()))
        .thenThrow(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, "Queued runs", 20, 20));
    String hourBefore = hourUtc();

    sweep(refused);
    makeDue(s.getId());
    sweep(refused);
    verify(workflowService, never()).recordRefusedSubmit(any(), any(), any(), any(), any(), any());
    makeDue(s.getId());
    sweep(refused);

    // The fire of an hourly schedule was due on the hour, whichever attempt dropped it.
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(workflowService, times(1))
        .recordRefusedSubmit(eq("t1"), eq("w1"), any(), eq(s.getId()), message.capture(), any());
    assertTrue(
        List.of(droppedMessage(hourBefore), droppedMessage(hourUtc())).contains(message.getValue()),
        message.getValue());
    assertEquals(
        WorkflowScheduleStatus.active,
        scheduleRepository.findById(s.getId()).orElseThrow().getStatus());
  }

  private static String droppedMessage(String firedAt) {
    return "Schedule \"nightly\" fired at "
        + firedAt
        + " UTC and no run started after 3 attempts. QUOTA_EXCEEDED: maximum queued runs";
  }

  private static String hourUtc() {
    return DateTimeFormatter.ofPattern("HH:00").withZone(ZoneOffset.UTC).format(Instant.now());
  }

  @Test
  void aRunOnceFireDroppedOnItsLastAttemptMovesToErrorAndIsRecorded() {
    WorkflowScheduleEntity s = activeRunOnce(Date.from(Instant.parse("2026-03-04T02:00:00Z")));
    s.setName("one-off");
    s.setRetryCount(2);
    scheduleRepository.save(s);
    ScheduleWatcher refused = watcherSubmittingThrough(service);
    when(workflowService.submit(any(), any(), any(), eq(true), any()))
        .thenThrow(new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED));

    sweep(refused);

    assertEquals(
        WorkflowScheduleStatus.error,
        scheduleRepository.findById(s.getId()).orElseThrow().getStatus());
    verify(workflowService, times(1))
        .recordRefusedSubmit(
            eq("t1"),
            eq("w1"),
            any(),
            eq(s.getId()),
            eq(
                "Schedule \"one-off\" fired at 02:00 UTC and no run started after 3 attempts."
                    + " WORKFLOWRUN_TRIGGER_DISABLED: trigger disabled"),
            any());
  }

  @Test
  void aDroppedFireThatFailedWithoutACodeNamesTheExceptionClassOnly() {
    WorkflowScheduleEntity s = activeRunOnce(Date.from(Instant.parse("2026-03-04T02:00:00Z")));
    s.setName("one-off");
    s.setRetryCount(2);
    scheduleRepository.save(s);
    ScheduleWatcher failing = watcherSubmittingThrough(service);
    when(workflowService.submit(any(), any(), any(), eq(true), any()))
        .thenThrow(new IllegalStateException("connection to db-internal:27017 refused"));

    sweep(failing);

    verify(workflowService, times(1))
        .recordRefusedSubmit(
            any(),
            any(),
            any(),
            any(),
            eq(
                "Schedule \"one-off\" fired at 02:00 UTC and no run started after 3 attempts."
                    + " IllegalStateException"),
            any());
  }

  @Test
  void aDroppedFireThatCannotBeRecordedStillSettlesTheSchedule() {
    WorkflowScheduleEntity s = activeCron(new Date(System.currentTimeMillis() - 1000));
    s.setRetryCount(2);
    scheduleRepository.save(s);
    ScheduleWatcher refused = watcherSubmittingThrough(service);
    refuseEverySubmit();
    when(workflowService.recordRefusedSubmit(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("cannot write"));

    sweep(refused);

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(WorkflowScheduleStatus.active, after.getStatus());
    assertEquals(0, after.getRetryCount());
  }

  // Force a schedule due again (a re-arm sets nextFireAt to the near future), preserving its state.
  private void makeDue(String id) {
    WorkflowScheduleEntity s = scheduleRepository.findById(id).orElseThrow();
    s.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
    scheduleRepository.save(s);
  }
}
