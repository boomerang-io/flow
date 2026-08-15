package io.boomerang.engine.model;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;

/**
 * Domain event published by the winner of a TaskRun Compare-And-Set transition. Carries ids and
 * from/to state only - listeners re-read the document for anything richer. {@code fromPhase} may
 * be {@code null} when the transition's guard does not pin the prior phase.
 */
public record TaskRunTransition(
    String id,
    String workflowRunRef,
    RunStatus fromStatus,
    RunPhase fromPhase,
    RunStatus toStatus,
    RunPhase toPhase) {}
