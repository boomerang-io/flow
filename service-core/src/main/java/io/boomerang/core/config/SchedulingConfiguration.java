package io.boomerang.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enable the scheduled jobs. Scheduling is application-wide, so each job carries its own switch:
 * the engine watcher {@code flow.watcher.enabled}, the schedule watcher {@code
 * flow.schedule.watcher.enabled}, and the outbox drain exists only when the event sink is enabled.
 * The two watcher switches exist so tests can invoke sweeps deterministically - never disable them
 * in production: the watchers are the recovery and schedule-firing paths.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {}
