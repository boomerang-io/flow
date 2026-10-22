package io.boomerang.common.model;


/**
 * Domain event published by the engine's RunScheduledWorkflow task when it needs a new Schedule
 * created. Replaces the former WorkflowClient.createSchedule() HTTP callback into
 * InternalController - the schedule module owns creation and listens for this event in-process.
 * Carries the exact WorkflowSchedule payload the engine builds today; the listener re-derives
 * anything else (e.g. team) the way InternalController's createSchedule endpoint did.
 */
public record ScheduleRequested(WorkflowSchedule schedule) {}
