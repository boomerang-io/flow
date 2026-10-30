package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.WorkflowType;
import lombok.Data;

/**
 * The fields of a workflow a patch may change without a new revision: what the workflow is to
 * other workspaces ({@code type}), which only the system workspace may set to a shared kind.
 */
@Data
@JsonInclude(Include.NON_NULL)
public class WorkflowPatch {

  private WorkflowType type;
}
