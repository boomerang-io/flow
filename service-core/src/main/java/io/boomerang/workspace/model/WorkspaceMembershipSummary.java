package io.boomerang.workspace.model;

import java.util.List;
import lombok.Value;

/*
 * The Workspace summaries (with Insights) and resolved Permissions for a set of team refs/roles a
 * User belongs to.
 *
 * Composed by WorkspaceService.getWorkspaceMembershipSummary and consumed by the api layer's Profile
 * composition (core.UserService cannot depend on workspace - see io.boomerang.core.UserService).
 */
@Value
public class WorkspaceMembershipSummary {
  List<WorkspaceSummary> teams;
  List<String> permissions;
}
