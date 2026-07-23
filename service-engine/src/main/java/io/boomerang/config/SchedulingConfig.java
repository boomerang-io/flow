package io.boomerang.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enable the watcher's scheduled sweeps. Disabling {@code flow.watcher.enabled} stops the
 * schedule (and the startup pass) while leaving the watcher bean available.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flow.watcher.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
