package io.boomerang.dispatcher;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.TaskRun;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The dispatcher's execution slots. A slot is taken when the engine hands over a TaskRun to
 * execute and given back when its executor work ends, a Pending pod included, so each task poll
 * asks the engine for no more than the slots still free. Termination orders take no slot.
 */
@Component
public class TaskSlots {

  private final int maxInFlight;

  private final AtomicInteger inFlight = new AtomicInteger();

  public TaskSlots(@Value("${flow.dispatcher.task.max-in-flight:25}") int maxInFlight) {
    this.maxInFlight = maxInFlight;
  }

  /** Whether the engine handed this TaskRun over to execute - what occupies a slot. */
  public static boolean isExecutionClaim(TaskRun taskRun) {
    return RunPhase.queued.equals(taskRun.getPhase()) && RunStatus.ready.equals(taskRun.getStatus());
  }

  /** Return the most new TaskRuns the next poll may claim, or null when no cap is set. */
  public Integer free() {
    return (maxInFlight > 0) ? Math.max(0, maxInFlight - inFlight.get()) : null;
  }

  public void take() {
    inFlight.incrementAndGet();
  }

  public void release() {
    inFlight.updateAndGet(count -> Math.max(0, count - 1));
  }
}
