package io.boomerang.common.model;

import lombok.Data;

/*
 * This is a fixed trigger model due to the UI.
 *
 * TODO: in future you could have a List<Trigger> in Workflow and delete this class
 */
@Data
public class WorkflowTrigger {

  private Trigger manual = new Trigger(true);
  private Trigger schedule = new Trigger(false);
  private Trigger webhook = new Trigger(false);
  private Trigger event = new Trigger(false);
  private Trigger github = new Trigger(false);

  @Override
  public String toString() {
    return "WorkflowTrigger [manual="
        + manual
        + ", schedule="
        + schedule
        + ", webhook="
        + webhook
        + ", event="
        + event
        + ", github="
        + github
        + "]";
  }
}
