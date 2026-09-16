package io.boomerang.workspace;

import static io.boomerang.common.util.DataAdapterUtil.filterValueByFieldType;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;

/*
 * Reads the one workspace engine mode has. "system" is the {workspace} path value for every
 * workspace-scoped route in engine mode, so the workspace resource itself has to resolve too.
 * WorkspaceService is standalone-only because it composes members, quotas and insights, none of
 * which exist here, so the read comes straight off the workspaces collection instead. Reads only:
 * creating, patching and deleting a workspace stay standalone.
 */
@Service
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineWorkspaceService {

  private static final String SYSTEM_WORKSPACE = "system";

  private final WorkspaceRepository workspaceRepository;

  public EngineWorkspaceService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
  }

  public Workspace get(String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ, name);
    }
    return workspaceRepository
        .findByNameIgnoreCase(name)
        .map(EngineWorkspaceService::toWorkspace)
        .orElseThrow(() -> new BoomerangException(BoomerangError.TEAM_INVALID_REF, name));
  }

  public Page<Workspace> query() {
    return new PageImpl<>(
        workspaceRepository
            .findByNameIgnoreCase(SYSTEM_WORKSPACE)
            .map(entity -> List.of(toWorkspace(entity)))
            .orElse(List.of()));
  }

  private static Workspace toWorkspace(WorkspaceEntity entity) {
    Workspace workspace = new Workspace(entity);
    filterValueByFieldType(workspace.getParameters(), false, FieldType.PASSWORD.value());
    return workspace;
  }
}
