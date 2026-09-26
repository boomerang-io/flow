package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/*
 * Runs a workflow task once per item of a list, in parallel, in the same run. Items is a JSON array
 * literal or one reference that resolves to a JSON array, for example
 * $(tasks.stage.results.batches). Inside the task $(params.item) is the item and $(params.index)
 * its position from 0.
 *
 * On a parent TaskRun the engine replaces items with the resolved array when it fans out, so a
 * recovery re-creates missing items from the same list without resolving again.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowTaskForeach {

  private Object items;
}
