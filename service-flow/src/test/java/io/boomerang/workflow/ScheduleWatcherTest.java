package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.client.EngineClient;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.config.MongoConfiguration;
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

  private WorkflowScheduleEntity activeCron(Date nextFireAt, String teamRef) {
    WorkflowScheduleEntity s = new WorkflowScheduleEntity();
    s.setWorkflowRef("w1");
    s.setTeamRef(teamRef);
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
    WorkflowScheduleEntity s = activeCron(due, "t1");
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    Date now = new Date();

    // Two instances race the same observed nextFireAt: exactly one wins the advance.
    WorkflowScheduleEntity a = service.tryClaimFire(s.getId(), due, next, now);
    WorkflowScheduleEntity b = service.tryClaimFire(s.getId(), due, next, now);
    assertTrue((a == null) ^ (b == null), "exactly one instance must win the fire");

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(next, after.getNextFireAt(), "nextFireAt advanced exactly once");
    assertNotNull(after.getLastFiredAt());
  }

  @Test
  void misfireCollapsesToNextFutureOccurrence() {
    Date longAgo = new Date(System.currentTimeMillis() - 86400000L * 30);
    WorkflowScheduleEntity s = activeCron(longAgo, "t1");
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    service.tryClaimFire(s.getId(), longAgo, next, new Date());
    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertTrue(
        after.getNextFireAt().after(new Date()),
        "a backlog collapses to the next future fire, never a catch-up storm");
  }

  @Test
  void initializeNextFireAtSetsWithoutFiring() {
    WorkflowScheduleEntity s = activeCron(null, null);
    Date next = service.nextOccurrence(HOURLY, "UTC", ZonedDateTime.now());
    service.initializeNextFireAt(s.getId(), next, "t1");

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertEquals(next, after.getNextFireAt());
    assertEquals("t1", after.getTeamRef());
    assertNull(after.getLastFiredAt(), "initialise must not fire");

    // Guarded on nextFireAt absent: a second initialise does not overwrite.
    service.initializeNextFireAt(s.getId(), new Date(0), "t2");
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
    WorkflowScheduleEntity s = activeCron(due, "t1");

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
  void watcherInitializesLegacyScheduleAndBackfillsTeam() {
    WorkflowScheduleEntity s = activeCron(null, null);
    when(relationshipService.getParentByLabel(any(), any(), eq("w1"))).thenReturn("t1");

    watcher.initializeSchedules();

    WorkflowScheduleEntity after = scheduleRepository.findById(s.getId()).orElseThrow();
    assertNotNull(after.getNextFireAt(), "legacy schedule gets a nextFireAt");
    assertEquals("t1", after.getTeamRef(), "teamRef is backfilled from the relationship");
    verify(scheduleJob, never()).execute(any(), any(), any());
  }
}
