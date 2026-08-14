package io.boomerang.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enable the watcher's scheduled sweeps. {@code flow.watcher.enabled=false} exists solely so tests
 * can invoke sweeps deterministically — never disable in production: the watcher is the recovery
 * path, and recovery must not be switchable off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flow.watcher.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
