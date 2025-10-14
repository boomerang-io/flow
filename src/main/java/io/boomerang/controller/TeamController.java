package io.boomerang.controller;

import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.boomerang.model.CreateFlowTeam;
import io.boomerang.model.FlowTeam;
import io.boomerang.model.TeamMember;
import io.boomerang.model.TeamQueryResult;
import io.boomerang.model.TeamWorkflowSummary;
import io.boomerang.model.WorkflowQuotas;
import io.boomerang.model.WorkflowSummary;
import io.boomerang.model.profile.SortSummary;
import io.boomerang.model.teams.ApproverGroupResponse;
import io.boomerang.model.teams.CreateApproverGroupRequest;
import io.boomerang.mongo.entity.FlowTeamConfiguration;
import io.boomerang.mongo.entity.FlowUserEntity;
import io.boomerang.mongo.entity.TeamEntity;
import io.boomerang.mongo.model.Quotas;
import io.boomerang.mongo.model.UserType;
import io.boomerang.security.service.UserValidationService;
import io.boomerang.service.UserIdentityService;
import io.boomerang.service.crud.TeamService;
import io.boomerang.service.crud.WorkflowService;

@RestController
@RequestMapping("/workflow")
public class TeamController {

  @Autowired
  private TeamService flowTeamService;

  @Autowired
  private UserIdentityService userIdentityService;

  @Autowired
  private UserValidationService userValidationService;

  @Autowired
  private WorkflowService workflowService;

  @PostMapping(value = "/teams")
  public void createCiTeam(@RequestBody CreateFlowTeam createCiTeamRequest) {
    flowTeamService.createFlowTeam(createCiTeamRequest.getCreatedGroupId(),
        createCiTeamRequest.getName());
  }

  @GetMapping(value = "/teams")
  public List<TeamWorkflowSummary> getTeams() {
    final FlowUserEntity user = userIdentityService.getCurrentUser();
    if (user == null) {
      return new LinkedList<>();
    } else if (user.getType() == UserType.admin) {
      return flowTeamService.getAllTeams();
    } else {
      return flowTeamService.getUserTeams(user);
    }
  }

  @GetMapping(value = "/teams/{teamId}/properties")
  public List<FlowTeamConfiguration> getAllTeamProperties(@PathVariable String teamId) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.getAllTeamProperties(teamId);
  }

  @GetMapping(value = "/teams/{teamId}/members")
  public List<TeamMember> getTeamMembers(@PathVariable String teamId) {
    return flowTeamService.getTeamMembers(teamId);
  }

  @DeleteMapping(value = "/teams/{teamId}/properties/{configurationId}")
  public void deleteTeamProperty(@PathVariable String teamId,
      @PathVariable String configurationId) {
    flowTeamService.deleteTeamProperty(teamId, configurationId);
  }

  @PatchMapping(value = "/teams/{teamId}/properties/{configurationId}")
  public List<FlowTeamConfiguration> updateTeamProperty(@PathVariable String teamId,
      @RequestBody FlowTeamConfiguration property, @PathVariable String configurationId) {
    return flowTeamService.updateTeamProperty(teamId, property);
  }

  @PostMapping(value = "/teams/{teamId}/properties")
  public FlowTeamConfiguration createNewTeamProperty(@PathVariable String teamId,
      @RequestBody FlowTeamConfiguration property) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.createNewTeamProperty(teamId, property);
  }

  @GetMapping(value = "/teams/{teamId}/quotas")
  public WorkflowQuotas getTeamQuotas(@PathVariable String teamId) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.getTeamQuotas(teamId);
  }

  @PutMapping(value = "/teams/{teamId}/quotas/default")
  public WorkflowQuotas resetTeamQuotas(@PathVariable String teamId) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.resetTeamQuotas(teamId);
  }

  @PatchMapping(value = "/teams/{teamId}/quotas")
  public Quotas updateTeamQuotas(@PathVariable String teamId, @RequestBody Quotas quotas) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.updateTeamQuotas(teamId, quotas);
  }

  @PutMapping(value = "/teams/{teamId}/quotas")
  public Quotas updateQuotasForTeam(@PathVariable String teamId, @RequestBody Quotas quotas) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.updateQuotasForTeam(teamId, quotas);
  }

  @GetMapping(value = "/quotas/default")
  public Quotas getDefaultQuotas() {
    return flowTeamService.getDefaultQuotas();
  }

  @GetMapping(value = "/manage/teams/{teamId}")
  public FlowTeam getTeam(@PathVariable String teamId) {
    userValidationService.validateUserForTeam(teamId);
    return flowTeamService.getTeamByIdDetailed(teamId);
  }

  @GetMapping(value = "/manage/teams")
  public TeamQueryResult getTeams(@RequestParam(required = false) String query,
      @RequestParam(defaultValue = "ASC") Direction order,
      @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {

    SortSummary sortSummary = new SortSummary();
    sortSummary.setProperty("name");
    sortSummary.setDirection(Direction.ASC.toString());

    Sort pagingSort = Sort.by(Direction.ASC, "name");

    if (StringUtils.isNotBlank(sort)) {
      Direction direction = order == null ? Direction.ASC : order;
      sortSummary.setDirection(direction.toString());
      sortSummary.setProperty(sort);

      pagingSort = Sort.by(direction, sort);
    }

    final Pageable pageable = PageRequest.of(page, size, pagingSort);

    TeamQueryResult response = flowTeamService.getAllAdminTeams(pageable);
    response.setupSortSummary(sortSummary);
    return response;
  }

  @DeleteMapping(value = "/teams/{teamId}")
  public TeamEntity deactivateTeam(@PathVariable String teamId) {
    return flowTeamService.deactivateTeam(teamId);
  }

  @GetMapping(value = "/teams/{teamId}/approvers")
  public List<ApproverGroupResponse> getApproverGroups(@PathVariable String teamId) {
    return flowTeamService.getTeamApproverGroups(teamId);
  }

  @PostMapping(value = "/teams/{teamId}/approvers")
  public ApproverGroupResponse createApproverGroup(@PathVariable String teamId,
      @RequestBody CreateApproverGroupRequest createApproverGroupRequest) {
    return flowTeamService.createApproverGroup(teamId, createApproverGroupRequest);
  }

  @PutMapping(value = "/teams/{teamId}/approvers/{groupId}")
  public ApproverGroupResponse updateApproverGroup(@PathVariable String teamId,
      @PathVariable String groupId, @RequestBody CreateApproverGroupRequest updateApproverGroup) {
    return flowTeamService.updateApproverGroup(teamId, groupId, updateApproverGroup);
  }

  @GetMapping(value = "/teams/{teamId}/approvers/{groupId}")
  public ApproverGroupResponse getSingleAproverGroup(@PathVariable String teamId,
      @PathVariable String groupId) {
    return flowTeamService.getSingleAproverGroup(teamId, groupId);
  }


  @DeleteMapping(value = "/teams/{teamId}/approvers/{groupId}")
  public void deleteApproverGroup(@PathVariable String teamId, @PathVariable String groupId) {
    flowTeamService.deleteApproverGroup(teamId, groupId);
  }

  @GetMapping(value = "/teams/{teamId}/workflows")
  public List<WorkflowSummary> getTeamWorkflows(@PathVariable String teamId) {
    return workflowService.getWorkflowsForTeam(teamId);
  }
}
