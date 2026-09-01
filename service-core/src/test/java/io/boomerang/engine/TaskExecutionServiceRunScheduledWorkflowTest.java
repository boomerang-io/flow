package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.ScheduleRequested;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A20: {@code TaskExecutionService.runScheduledWorkflow} used {@code java.util.Calendar} with
 * {@code Calendar.HOUR} (the 0-11 field, not {@code HOUR_OF_DAY}) to set the "time" param's
 * clock-hour - so {@code time=14:30} was silently read back as {@code 02:30}. These tests pin
 * the java.time rewrite: the requested clock time lands unchanged in the target zone for every
 * {@code futurePeriod} unit, including the 24-hour boundary case that exposed the original bug.
 *
 * <p>Invoked via reflection since the method is private and the class is G1-gated (its
 * constructor is the full engine dependency graph) - only the one field the method touches
 * ({@code eventPublisher}) is set directly, avoiding a heavyweight Spring context for a pure
 * date-arithmetic test.
 */
class TaskExecutionServiceRunScheduledWorkflowTest {

  private TaskExecutionService service;
  private ApplicationEventPublisher eventPublisher;

  @BeforeEach
  void setUp() throws Exception {
    service = mock(TaskExecutionService.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    Field field = TaskExecutionService.class.getDeclaredField("eventPublisher");
    field.setAccessible(true);
    field.set(service, eventPublisher);
  }

  private Date invoke(String futureIn, String futurePeriod, String timezone, String time)
      throws Exception {
    TaskRunEntity taskRun = new TaskRunEntity();
    taskRun.setName("run-scheduled-workflow");
    // Fixed creation instant: 2026-01-01T00:00:00Z, so every unit's expected result is easy to
    // reason about and the test is not time-of-run dependent.
    taskRun.setCreationDate(Date.from(LocalDateTime.of(2026, 1, 1, 0, 0, 0).toInstant(java.time.ZoneOffset.UTC)));
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("workflowRef", "wf-1"));
    params.add(new RunParam("futureIn", futureIn));
    params.add(new RunParam("futurePeriod", futurePeriod));
    params.add(new RunParam("timezone", timezone));
    params.add(new RunParam("time", time));
    taskRun.setParams(params);

    Method method =
        TaskExecutionService.class.getDeclaredMethod(
            "runScheduledWorkflow",
            TaskRunEntity.class,
            io.boomerang.common.entity.WorkflowRunEntity.class);
    method.setAccessible(true);
    method.invoke(service, taskRun, null);

    assertThat(taskRun.getStatus()).isEqualTo(RunStatus.succeeded);

    ArgumentCaptor<ScheduleRequested> captor = ArgumentCaptor.forClass(ScheduleRequested.class);
    verify(eventPublisher).publishEvent(captor.capture());
    return captor.getValue().schedule().getDateSchedule();
  }

  @Test
  void twentyFourHourTimeIsNotFoldedTo12Hour() throws Exception {
    // The exact bug case from A20's brief: time=14:30 must stay 14:30, not fold to 02:30.
    Date result = invoke("1", "days", "UTC", "14:30");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(14, 30));
    assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 2));
  }

  @ParameterizedTest
  @CsvSource({"00:05", "09:15", "12:00", "14:30", "23:59"})
  void everyHourOfDayRoundTripsInTargetZone(String time) throws Exception {
    Date result = invoke("2", "days", "America/New_York", time);
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("America/New_York"));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.parse(time));
  }

  @Test
  void singleDigitHourIsTolerated() throws Exception {
    // The old String.split(":") + Integer.valueOf(...) accepted "9:05" as well as "09:05".
    Date result = invoke("1", "weeks", "UTC", "9:05");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(9, 5));
  }

  @Test
  void daysUnitAddsCalendarDays() throws Exception {
    Date result = invoke("3", "days", "UTC", "10:00");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 4));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  void weeksUnitAddsSevenDaysPerWeek() throws Exception {
    Date result = invoke("2", "weeks", "UTC", "10:00");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  void monthsUnitAddsCalendarMonths() throws Exception {
    Date result = invoke("2", "months", "UTC", "10:00");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  void hoursUnitIgnoresTimeAndTimezoneParams() throws Exception {
    // Pre-existing quirk, preserved: for minutes/hours granularity the time-of-day override
    // never runs, so "timezone" is read but has no effect on the result.
    Date result = invoke("5", "hours", "America/New_York", "14:30");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt).isEqualTo(ZonedDateTime.of(2026, 1, 1, 5, 0, 0, 0, ZoneId.of("UTC")));
  }

  @Test
  void minutesUnitIgnoresTimeAndTimezoneParams() throws Exception {
    Date result = invoke("90", "minutes", "America/New_York", "14:30");
    ZonedDateTime zdt = result.toInstant().atZone(ZoneId.of("UTC"));
    assertThat(zdt).isEqualTo(ZonedDateTime.of(2026, 1, 1, 1, 30, 0, 0, ZoneId.of("UTC")));
  }
}
