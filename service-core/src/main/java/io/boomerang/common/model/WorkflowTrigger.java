package io.boomerang.common.model;

import lombok.Data;

/*
 * This is a fixed trigger model due to the UI. A trigger left null was not sent: an update keeps the
 * stored one, and a read fills the default (manual on, the rest off) - see ConvertUtil.
 *
 * TODO: in future you could have a List<Trigger> in Workflow and delete this class
 */
@Data
public class WorkflowTrigger {

  private Trigger manual;
  private Trigger schedule;
  private Trigger webhook;
  private Trigger event;
  private Trigger github;

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
