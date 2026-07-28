package io.boomerang.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enable the ScheduleWatcher's fire sweep. {@code flow.schedule.watcher.enabled=false} exists only
 * so tests can invoke the sweeps deterministically - never disable in production: the sweep is the
 * schedule-firing path, and firing must not be switchable off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "flow.schedule.watcher.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduleWatcherConfig {}
