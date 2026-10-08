package io.boomerang.workspace;

import static io.boomerang.common.util.DataAdapterUtil.filterValueByFieldType;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceSummary;
import io.boomerang.workspace.model.WorkspaceSummaryInsights;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;

/*
 * The one workspace engine mode has. "system" is the {workspace} path value for every
 * workspace-scoped route in engine mode, so the workspace resource itself has to resolve too.
 * WorkspaceService is standalone-only because it composes members, quotas and insights, none of
 * which exist here, so the workspace is read straight off the workspaces collection instead. A
 * single-workspace installation still has workspace parameters, so those are written here through
 * the same WorkspaceParameterService standalone uses; creating, renaming and deleting a workspace,
 * and its members, quotas and approver groups, stay standalone.
 */
@Service
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineWorkspaceService {

  private static final String SYSTEM_WORKSPACE = "system";

  private final WorkspaceRepository workspaceRepository;
  private final RelationshipService relationshipService;
  private final WorkspaceParameterService workspaceParameterService;
  private final ParamLayerCache paramLayerCache;

  public EngineWorkspaceService(
      WorkspaceRepository workspaceRepository,
      RelationshipService relationshipService,
      WorkspaceParameterService workspaceParameterService,
      ParamLayerCache paramLayerCache) {
    this.workspaceRepository = workspaceRepository;
    this.relationshipService = relationshipService;
    this.workspaceParameterService = workspaceParameterService;
    this.paramLayerCache = paramLayerCache;
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

  /*
   * Update the workspace's parameters, the only part of it engine mode writes. A request that
   * changes anything else - name, display name, status, type, labels, quotas, members or approver
   * groups - is refused with TEAM_INVALID_REQ rather than half-applied.
   */
  public Workspace patch(String name, WorkspaceRequest request) {
    if (request == null || changesMoreThanParameters(request)) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ, name);
    }
    WorkspaceEntity workspaceEntity = find(name);
    if (request.getParameters() != null && !request.getParameters().isEmpty()) {
      workspaceEntity.setParameters(
          workspaceParameterService.createOrUpdateParameters(
              workspaceEntity.getParameters(), request.getParameters()));
      workspaceRepository.save(workspaceEntity);
      paramLayerCache.evictAll();
    }
    return toWorkspace(workspaceEntity);
  }

  public void deleteParameter(String name, String parameter) {
    workspaceParameterService.deleteParameter(find(name), parameter);
  }

  private WorkspaceEntity find(String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ, name);
    }
    return workspaceRepository
        .findByNameIgnoreCase(name)
        .orElseThrow(() -> new BoomerangException(BoomerangError.TEAM_INVALID_REF, name));
  }

  private static boolean changesMoreThanParameters(WorkspaceRequest request) {
    return request.getName() != null
        || request.getDisplayName() != null
        || request.getStatus() != null
        || request.getType() != null
        || request.getExternalRef() != null
        || request.getLabels() != null
        || request.getQuotas() != null
        || request.getMembers() != null
        || request.getApproverGroups() != null;
  }

  public Page<Workspace> query() {
    return new PageImpl<>(
        workspaceRepository
            .findByNameIgnoreCase(SYSTEM_WORKSPACE)
            .map(entity -> List.of(toWorkspace(entity)))
            .orElse(List.of()));
  }

  /*
   * The profile's workspace list: the system workspace and its workflow count. Engine mode has no
   * members, so the member count is zero.
   */
  public List<WorkspaceSummary> summaries() {
    return workspaceRepository
        .findByNameIgnoreCase(SYSTEM_WORKSPACE)
        .map(
            entity -> {
              WorkspaceSummary summary = new WorkspaceSummary(entity);
              WorkspaceSummaryInsights insights = new WorkspaceSummaryInsights();
              insights.setMembers(0L);
              insights.setWorkflows(
                  (long)
                      relationshipService
                          .filter(
                              RelationshipType.WORKFLOW,
                              Optional.empty(),
                              Optional.of(RelationshipType.WORKSPACE),
                              Optional.of(List.of(entity.getId())))
                          .size());
              summary.setInsights(insights);
              return List.of(summary);
            })
        .orElse(List.of());
  }

  private static Workspace toWorkspace(WorkspaceEntity entity) {
    Workspace workspace = new Workspace(entity);
    filterValueByFieldType(workspace.getParameters(), false, FieldType.PASSWORD.value());
    return workspace;
  }
}
