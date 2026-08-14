package io.boomerang.workflow;

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

import io.boomerang.client.EngineClient;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.RelationshipService;
import io.boomerang.workflow.repository.WorkflowScheduleRepository;
import java.time.ZonedDateTime;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  private ScheduleJob scheduleJob;
  private RelationshipService relationshipService;
  private ScheduleWatcher watcher;

  @BeforeEach
  void setUp() {
    scheduleRepository.deleteAll();
    relationshipService = mock(RelationshipService.class);
    service =
        new ScheduleService(
            scheduleRepository,
            mock(WorkflowService.class),
            relationshipService,
            mock(EngineClient.class),
            mongoTemplate);
    scheduleJob = mock(ScheduleJob.class);
    watcher = new ScheduleWatcher(scheduleRepository, service, scheduleJob, relationshipService);
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

  // Force a schedule due again (a re-arm sets nextFireAt to the near future), preserving its state.
  private void makeDue(String id) {
    WorkflowScheduleEntity s = scheduleRepository.findById(id).orElseThrow();
    s.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
    scheduleRepository.save(s);
  }
}
