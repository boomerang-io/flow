package io.boomerang.engine.model;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;

/**
 * Domain event published by the winner of a WorkflowRun Compare-And-Set transition. Carries ids
 * and from/to state only - listeners re-read the document for anything richer.
 */
public record WorkflowRunTransition(
    String id,
    String workflowRef,
    RunStatus fromStatus,
    RunPhase fromPhase,
    RunStatus toStatus,
    RunPhase toPhase) {}
