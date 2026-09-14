package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Engine → dispatcher: the subset of a {@link WorkspaceReleaseQuery} whose volumes may go. */
@Data
public class WorkspaceReleaseResponse {
  private List<String> workflowRunRefs = new ArrayList<>();
  private List<String> workflowRefs = new ArrayList<>();
}
