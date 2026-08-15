package io.boomerang.workspace;

import static io.boomerang.common.util.DataAdapterUtil.filterValueByFieldType;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.WorkflowCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.StringUtil;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.*;
import io.boomerang.core.model.*;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.security.IdentityService;
import io.boomerang.workspace.entity.ApproverGroupEntity;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.ApproverGroup;
import io.boomerang.workspace.model.ApproverGroupRequest;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceMembershipSummary;
import io.boomerang.workspace.model.WorkspaceNameCheckRequest;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceStatus;
import io.boomerang.workspace.model.WorkspaceSummary;
import io.boomerang.workspace.model.WorkspaceSummaryInsights;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import io.boomerang.workspace.repository.WorkspaceRepository;
import io.boomerang.api.WorkspaceTaskService;
import io.boomerang.api.WorkspaceWorkflowService;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

// E8: workspace is a full-mode-only module root per the mode matrix.
@Service
@ConditionalOnFlowMode(FlowMode.FULL)
public class WorkspaceService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final List<String> RESERVED_TEAM_NAMES =
      List.of("home", "admin", "system", "profile", "connect");
  public static final String TEAMS_SETTINGS_KEY = "teams";
  public static final String QUOTA_MAX_WORKFLOW_COUNT = "max.workflow.count";
  public static final String QUOTA_MAX_WORKFLOW_STORAGE = "max.workflow.storage";
  public static final String QUOTA_MAX_WORKFLOWRUN_CONCURRENT = "max.workflowrun.concurrent";
  public static final String QUOTA_MAX_WORKFLOWRUN_MONTHLY = "max.workflowrun.monthly";
  public static final String QUOTA_MAX_WORKFLOWRUN_DURATION = "max.workflowrun.duration";
  public static final String QUOTA_MAX_WORKFLOWRUN_STORAGE = "max.workflowrun.storage";

  private final WorkspaceRepository workspaceRepository;
  private final IdentityService identityService;
  private final UserService userService;
  private final ApproverGroupRepository approverGroupRepository;
  private final RoleRepository roleRepository;
  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final InsightsService insightsService;
  private final WorkspaceWorkflowService workspaceWorkflowService;
  private final TokenService tokenService;
  private final WorkspaceTaskService workspaceTaskService;

  public WorkspaceService(
      WorkspaceRepository workspaceRepository,
      IdentityService identityService,
      UserService userService,
      ApproverGroupRepository approverGroupRepository,
      RoleRepository roleRepository,
      SettingsService settingsService,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      InsightsService insightsService,
      WorkspaceWorkflowService workspaceWorkflowService,
      TokenService tokenService,
      WorkspaceTaskService workspaceTaskService) {
    this.workspaceRepository = workspaceRepository;
    this.identityService = identityService;
    this.userService = userService;
    this.approverGroupRepository = approverGroupRepository;
    this.roleRepository = roleRepository;
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.insightsService = insightsService;
    this.workspaceWorkflowService = workspaceWorkflowService;
    this.tokenService = tokenService;
    this.workspaceTaskService = workspaceTaskService;
  }

  /*
   * Validate the team name - used by the UI to determine if a team can be created
   */
  public ResponseEntity<?> validateName(WorkspaceNameCheckRequest request) {
    if (request.getName() != null && !request.getName().isBlank()) {
      String kebabName = StringUtil.kebabCase(request.getName());

      // Ensures unique team name (slug)
      if (relationshipService.doesSlugOrRefExistForType(RelationshipType.TEAM, kebabName)
          || RESERVED_TEAM_NAMES.contains(kebabName)) {
        throw new BoomerangException(BoomerangError.TEAM_NON_UNIQUE_NAME);
      }
      return ResponseEntity.ok().build();
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
  }

  /*
   * Retrieve a single team
   */
  public Workspace get(String team) {
    if (!Objects.isNull(team) && !team.isBlank()) {
      if (relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        Optional<WorkspaceEntity> entity = workspaceRepository.findByNameIgnoreCase(team);
        if (entity.isPresent()) {
          return convertWorkspaceEntityToWorkspace(entity.get());
        }
      }
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
  }

  /*
   * Creates a new Workspace
   *
   * - Name must not be blank
   * - Display name must not be blank
   */
  public Workspace create(WorkspaceRequest request) {
    if (!request.getName().isBlank() && !request.getDisplayName().isBlank()) {
      // Validate name - will throw exception if not valid
      WorkspaceNameCheckRequest checkRequest = new WorkspaceNameCheckRequest(request.getName());
      this.validateName(checkRequest);

      /*
       * Create WorkspaceEntity & Copy majority of fields.
       * - Status is ignored - can only be active
       * - Members, quotas, parameters, and approverGroups need further logic
       */
      WorkspaceEntity workspaceEntity = new WorkspaceEntity();
      BeanUtils.copyProperties(
          request, workspaceEntity, "id", "status", "members", "quotas", "parameters", "approverGroups");

      // Set custom quotas
      // Don't set default quotas as they can change over time and should be dynamic
      Quotas quotas = new Quotas();
      // Override quotas based on creation request
      setCustomQuotas(quotas, request.getQuotas());
      workspaceEntity.setQuotas(quotas);

      // Create / Update Parameters
      if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        workspaceEntity.setParameters(
            createOrUpdateParameters(workspaceEntity.getParameters(), request.getParameters()));
      }

      // Create / Update ApproverGroups
      if (request.getApproverGroups() != null && !request.getApproverGroups().isEmpty()) {
        createOrUpdateApproverGroups(workspaceEntity, request.getApproverGroups());
      }

      workspaceEntity = workspaceRepository.save(workspaceEntity);
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.TEAM,
          workspaceEntity.getId(),
          workspaceEntity.getName(),
          Optional.empty(),
          Optional.empty());

      // Create Member Relationships
      createOrUpdateUserRelationships(workspaceEntity.getName(), request.getMembers());

      return convertWorkspaceEntityToWorkspace(workspaceEntity);
    } else {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
  }

  /*
   * Patch team
   */
  public Workspace patch(String team, WorkspaceRequest request) {
    if (request != null) {
      LOGGER.debug("Request: " + request.toString());
      if (team == null || team.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      if (!relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
      if (!optWorkspaceEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();
      boolean updatedName = false;
      String originalName = workspaceEntity.getName();
      if (request.getName() != null && !request.getName().isBlank()) {
        workspaceEntity.setName(request.getName());
        updatedName = true;
      }
      if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
        workspaceEntity.setDisplayName(request.getDisplayName());
      }
      if (request.getStatus() != null) {
        workspaceEntity.setStatus(request.getStatus());
      }
      if (request.getExternalRef() != null && !request.getExternalRef().isBlank()) {
        workspaceEntity.setExternalRef(request.getExternalRef());
      }
      if (request.getLabels() != null && !request.getLabels().isEmpty()) {
        workspaceEntity.getLabels().putAll(request.getLabels());
      }

      // Set custom quotas
      // Don't set default quotas as they can change over time and should be dynamic
      Quotas quotas = new Quotas();
      // Override quotas based on creation request
      setCustomQuotas(quotas, request.getQuotas());
      workspaceEntity.setQuotas(quotas);

      // Create / Update Parameters
      if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        LOGGER.debug("Request Parameters: " + request.getParameters().toString());
        workspaceEntity.setParameters(
            createOrUpdateParameters(workspaceEntity.getParameters(), request.getParameters()));
      }

      // Create / Update ApproverGroups
      if (request.getApproverGroups() != null && !request.getApproverGroups().isEmpty()) {
        createOrUpdateApproverGroups(workspaceEntity, request.getApproverGroups());
      }

      workspaceRepository.save(workspaceEntity);

      // Update any existing relationships if the name has changed
      if (updatedName) {
        relationshipService.updateNodeByRefOrSlug(
            RelationshipType.TEAM, originalName, request.getName());
      }

      // Create / Update Relationships for Users
      createOrUpdateUserRelationships(workspaceEntity.getName(), request.getMembers());
      return convertWorkspaceEntityToWorkspace(workspaceEntity);
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
  }

  /*
   * Destructive cascade Workspace deletion
   */
  public void delete(String team) {
    if (team == null || team.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }

    // If no relationship, user has no access or team doesn't exist
    if (!relationshipService.check(
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }

    // Get and delete all Workflows (this cascade deletes the Workflows, WorkflowRevisions,
    // Schedules, Actions, WorkflowRuns, and TaskRuns
    List<String> workflowRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.empty(),
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)));
    LOGGER.debug("Workspace Workflow Refs: {}", workflowRefs.toString());
    if (workflowRefs.size() > 0) {
      workflowRefs.forEach(ref -> workspaceWorkflowService.delete(team, ref));
    }

    // Delete all Tokens
    tokenService.deleteAllForPrincipal(team);

    // Delete all Workspace Tasks
    List<String> templateRefs =
        relationshipService.filter(
            RelationshipType.TASK,
            Optional.empty(),
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)));
    if (templateRefs.size() > 0) {
      templateRefs.forEach(ref -> workspaceTaskService.delete(ref, team));
    }

    // TODO - Delete Workspace Integration Installations

    // Delete Workspace
    workspaceRepository.deleteByName(team);

    // Delete Workspace relationship node
    relationshipService.removeNodeAndEdgeByRefOrSlug(RelationshipType.TEAM, team);
  }

  /*
   * Query for Teams
   *
   * Returns Teams plus each Teams UserRefs, WorkflowRefs, and Quotas
   */
  public Page<Workspace> query(
      Optional<Integer> queryPage,
      Optional<Integer> queryLimit,
      Optional<Direction> queryOrder,
      Optional<String> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryTeams) {
    List<String> teamRefs = new LinkedList<>();
    teamRefs =
        relationshipService.filter(
            RelationshipType.TEAM, queryTeams, Optional.empty(), Optional.empty(), true);
    LOGGER.debug("TeamRefs: " + teamRefs.toString());

    return findByCriteria(
        queryPage, queryLimit, queryOrder, querySort, queryLabels, queryStatus, teamRefs);
  }

  private Page<Workspace> findByCriteria(
      Optional<Integer> queryPage,
      Optional<Integer> queryLimit,
      Optional<Direction> queryOrder,
      Optional<String> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      List<String> teamRefs) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort =
        Sort.by(new Order(queryOrder.orElse(Direction.ASC), querySort.orElse("name")));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }

    List<Criteria> criteriaList = new ArrayList<>();
    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryStatus.isPresent()) {
      if (queryStatus.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(WorkspaceStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    Criteria criteria = Criteria.where("name").in(teamRefs);
    criteriaList.add(criteria);

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    List<WorkspaceEntity> workspaceEntities = mongoTemplate.find(query, WorkspaceEntity.class);

    LOGGER.debug("Found " + workspaceEntities.size() + " teams.");
    List<Workspace> teams = new LinkedList<>();
    if (!workspaceEntities.isEmpty()) {
      workspaceEntities.forEach(workspaceEntity -> teams.add(convertWorkspaceEntityToWorkspace(workspaceEntity)));
    }

    Page<Workspace> pages =
        PageableExecutionUtils.getPage(
            teams, pageable, () -> mongoTemplate.count(query, WorkspaceEntity.class));

    return pages;
  }

  public void removeMembers(String team, List<WorkspaceMember> request) {
    if (request != null && !request.isEmpty()) {
      if (team == null || team.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      if (!relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
      if (!optWorkspaceEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      List<String> userRefs = new LinkedList<>();
      for (WorkspaceMember userSummary : request) {
        Optional<User> userEntity = Optional.empty();
        if (!userSummary.getId().isEmpty()) {
          userEntity = userService.getUserByID(userSummary.getId());
        } else if (!userSummary.getEmail().isEmpty()) {
          userEntity = userService.getUserByEmail(userSummary.getEmail());
        }
        if (userEntity.isPresent()) {
          userRefs.add(userEntity.get().getId());
        }
      }
      if (!userRefs.isEmpty()) {
        userRefs.forEach(
            userRef ->
                relationshipService.removeEdge(
                    RelationshipType.USER, userRef, RelationshipType.TEAM, team));
      }
    }
  }

  /*
   *  Allows only the requesting user to leave the team
   *
   *  TODO: ensure the remaining owner cannot leave the team
   */
  public void leave(String team) {
    if (team == null || team.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    if (!relationshipService.check(
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    relationshipService.removeEdge(RelationshipType.TEAM, team);
  }

  /*
   * Creates or Updates Workspace Parameters
   */
  private List<AbstractParam> createOrUpdateParameters(
      List<AbstractParam> parameters, List<AbstractParam> request) {
    if (!request.isEmpty()) {
      LOGGER.debug("Starting Parameters: " + parameters.toString());
      List<String> names = request.stream().map(AbstractParam::getName).toList();
      // Check if parameter exists and remove
      parameters =
          parameters.stream()
              .filter(p -> !names.contains(p.getName()))
              .collect(Collectors.toList());

      // Add all new / updated params
      parameters.addAll(request);
    }
    LOGGER.debug("Ending Parameters: " + parameters.toString());
    return parameters;
  }

  /*
   * Delete parameters by key
   */
  public void deleteParameter(String team, String name) {
    if (team == null || team.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    if (!relationshipService.check(
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();

    if (workspaceEntity.getParameters() != null) {
      List<AbstractParam> parameters = workspaceEntity.getParameters();
      Optional<AbstractParam> optionalParameter =
          parameters.stream().filter(p -> p.getName().equals(name)).findAny();
      if (optionalParameter.isPresent()) {
        parameters.remove(optionalParameter.get());
        workspaceEntity.setParameters(parameters);
        workspaceRepository.save(workspaceEntity);
      } else {
        throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
      }
    }
  }

  /*
   * Create & Update Approver Group
   *
   * - Creates a relationship against a team
   * - ApproverGroup name must be unique per team
   */
  private void createOrUpdateApproverGroups(
      WorkspaceEntity workspaceEntity, List<ApproverGroupRequest> request) {
    List<ApproverGroupEntity> approverGroupEntities =
        getApproverGroupsForTeam(workspaceEntity.getName());

    for (ApproverGroupRequest r : request) {
      // Ensure ApproverGroupName is not blank or null
      if (r.getName() == null || r.getName().isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }

      ApproverGroupEntity age =
          approverGroupEntities.stream()
              .filter(e -> e.getName().equalsIgnoreCase(r.getName()))
              .findFirst()
              .orElse(null);

      if (age != null) {
        LOGGER.debug("Existing ApproverGroup: " + age.toString());
        // ApproverGroup already exists - update
        approverGroupEntities.remove(age);
        age.setName(r.getName());

        // Ensure each approver is a valid team member
        if (r.getApprovers() != null) {
          Map<String, String> membersAndRoles =
              relationshipService.membersAndRoles(workspaceEntity.getName());
          LOGGER.debug("User Refs: " + membersAndRoles.keySet().toString());
          List<String> validApproverRefs =
              r.getApprovers().stream()
                  .filter(a -> membersAndRoles.containsKey(a))
                  .collect(Collectors.toList());
          LOGGER.debug("Valid Approver Refs: " + validApproverRefs.toString());
          age.setApprovers(validApproverRefs);

          age = approverGroupRepository.save(age);
        }
      } else {
        // ApproverGroup + Relationship needs creating
        ApproverGroupEntity approverGroupEntity = new ApproverGroupEntity();
        approverGroupEntity.setName(r.getName());
        if (r.getApprovers() != null) {
          Map<String, String> membersAndRoles =
              relationshipService.membersAndRoles(workspaceEntity.getName());
          LOGGER.debug("User Refs: " + membersAndRoles.keySet().toString());
          List<String> validApproverRefs =
              r.getApprovers().stream()
                  .filter(a -> membersAndRoles.containsKey(a))
                  .collect(Collectors.toList());
          LOGGER.debug("Valid Approver Refs: " + validApproverRefs.toString());
          approverGroupEntity.setApprovers(validApproverRefs);
        }
        approverGroupEntity = approverGroupRepository.save(approverGroupEntity);
        relationshipService.createNodeAndEdge(
            RelationshipType.TEAM,
            workspaceEntity.getId(),
            RelationshipLabel.HAS_APPROVER_GROUP,
            RelationshipType.APPROVERGROUP,
            approverGroupEntity.getId(),
            approverGroupEntity.getName(),
            Optional.empty(),
            Optional.empty());
      }
    }
  }

  // Retrieve ApproverGroups by relationship as they are stored separately to the WorkspaceEntity
  private List<ApproverGroupEntity> getApproverGroupsForTeam(String team) {
    List<String> approverGroupRefs =
        relationshipService.filter(
            RelationshipType.APPROVERGROUP,
            Optional.empty(),
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)));
    List<ApproverGroupEntity> approverGroupEntities =
        approverGroupRepository.findByIdIn(approverGroupRefs);
    return approverGroupEntities;
  }

  /*
   * Delete an Approver Group
   *
   * - Removes relationship as well
   */
  public void deleteApproverGroups(String team, List<String> request) {
    if (team == null || team.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    if (!relationshipService.check(
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }

    for (String r : request) {
      Optional<ApproverGroupEntity> ag = approverGroupRepository.findById(r);
      if (ag.isPresent()) {
        approverGroupRepository.deleteById(r);
        relationshipService.removeNodeAndEdgeByRefOrSlug(RelationshipType.APPROVERGROUP, r);
      }
    }
  }

  /*
   * Delete custom quotas on the team and reset back to default
   */
  public void deleteCustomQuotas(String team) {
    if (team == null || team.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    if (!relationshipService.check(
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();

    // Delete any custom quotas set on the team
    // This will then reset and default to the Workspace Quotas set in Settings
    workspaceEntity.setQuotas(new Quotas());
    workspaceRepository.save(workspaceEntity);
  }

  /*
   * Reset quotas to default (i.e. delete custom quotas on the team)
   */
  public ResponseEntity<Quotas> getDefaultQuotas() {
    return ResponseEntity.ok(setDefaultQuotas());
  }

  /*
   * Used by WorkflowRun Service to ensure Workflow can run
   */
  public CurrentQuotas getCurrentQuotas(String team) {
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (optWorkspaceEntity.isPresent()) {
      Quotas quotas = setDefaultQuotas();
      setCustomQuotas(quotas, optWorkspaceEntity.get().getQuotas());
      CurrentQuotas currentQuotas = new CurrentQuotas(quotas);
      setCurrentQuotas(currentQuotas, team);
      return currentQuotas;
    }
    return null;
  }

  //
  // private void setWorkflowStorage(List<WorkflowSummary> workflows, WorkflowQuotas workflowQuotas)
  // {
  // Integer currentWorkflowsPersistentStorage = 0;
  // if(workflows != null) {
  // for (WorkflowSummary workflow : workflows) {
  // if (workflow.getStorage() == null) {
  // workflow.setStorage(new Storage());
  // }
  // if (workflow.getStorage().getActivity() == null) {
  // workflow.getStorage().setActivity(new ActivityStorage());
  // }
  //
  // if (workflow.getStorage().getActivity().getEnabled()) {
  // currentWorkflowsPersistentStorage += 1;
  // }
  // }
  // }
  // workflowQuotas.setCurrentWorkflowsPersistentStorage(currentWorkflowsPersistentStorage);
  // }
  //

  /*
   * Builds the WorkspaceSummary (with Insights) list and resolved Permissions for the given
   * team-ref -> role map, skipping any ref that no longer resolves to an existing Workspace (stale
   * relationship entries).
   *
   * Used to compose the User Profile response (api layer) - the rollup needs both User
   * (core) and Workspace (workspace) data, so it can't live in core.UserService.
   */
  public WorkspaceMembershipSummary getWorkspaceMembershipSummary(Map<String, String> teamRefsAndRoles) {
    List<WorkspaceSummary> teamSummaries = new LinkedList<>();
    List<String> permissions = new LinkedList<>();
    teamRefsAndRoles.forEach(
        (k, v) -> {
          Optional<WorkspaceEntity> workspaceEntity = workspaceRepository.findById(k);
          if (workspaceEntity.isPresent()) {
            // Generate WorkspaceSummary + Insight
            WorkspaceSummary ts = new WorkspaceSummary(workspaceEntity.get());
            WorkspaceSummaryInsights tsi = new WorkspaceSummaryInsights();
            Map<String, String> membersAndRoles = relationshipService.membersAndRoles(k);
            tsi.setMembers(Long.valueOf(membersAndRoles.size()));
            List<String> workflowRefs =
                relationshipService.filter(
                    RelationshipType.WORKFLOW,
                    Optional.empty(),
                    Optional.of(RelationshipType.TEAM),
                    Optional.of(List.of(k)));
            tsi.setWorkflows(Long.valueOf(workflowRefs.size()));
            ts.setInsights(tsi);
            teamSummaries.add(ts);

            // Generate Permissions
            roleRepository.findByTypeAndName("team", v).getPermissions().stream()
                .forEach(p -> permissions.add(p.replace("{principal}", k)));
          }
        });
    return new WorkspaceMembershipSummary(teamSummaries, permissions);
  }

  /*
   * Return all team level roles
   */
  public ResponseEntity<List<Role>> getRoles() {
    List<RoleEntity> roleEntities = roleRepository.findByType("team");
    List<Role> roles = new LinkedList<>();
    roleEntities.forEach(
        re -> {
          roles.add(new Role(re));
        });
    return ResponseEntity.ok(roles);
  }

  /*
   * Converts the Workspace Entity to Model and adds the extra Users, WorkflowRefs, ApproverGroupRefs,
   * Quotas
   */
  private Workspace convertWorkspaceEntityToWorkspace(WorkspaceEntity workspaceEntity) {
    Workspace team = new Workspace(workspaceEntity);

    //    List<WorkflowSummary> summary = new LinkedList<>();
    //    try {
    //      WorkflowResponsePage response = workspaceWorkflowService.query(Optional.empty(),
    // Optional.empty(), Optional.of(Direction.ASC), Optional.empty(), Optional.empty(),
    // Optional.of(List.of(workspaceEntity.getId())), Optional.empty());
    //      if (response.getContent() != null && !response.getContent().isEmpty()) {
    //        List<Workflow> workflows = response.getContent();
    //        workflows.forEach(w -> summary.add(new WorkflowSummary(w)));
    //      }
    //    } catch (BoomerangException e) {
    //      LOGGER.error("convertWorkspaceEntityToWorkspace() - issue in retrieving Workflows for this team.
    // Most likely cause is page size is being returned as 0");
    //    }
    //    team.setWorkflows(summary);

    // Get Members
    team.setMembers(getUsersForTeam(workspaceEntity.getName()));

    // Get default & custom stored Quotas
    Quotas quotas = setDefaultQuotas();
    setCustomQuotas(quotas, workspaceEntity.getQuotas());
    CurrentQuotas currentQuotas = new CurrentQuotas(quotas);
    setCurrentQuotas(currentQuotas, workspaceEntity.getName());
    team.setQuotas(currentQuotas);

    // Get Approver Groups
    List<ApproverGroupEntity> approverGroupEntities =
        getApproverGroupsForTeam(workspaceEntity.getName());
    List<ApproverGroup> approverGroups = new LinkedList<>();
    approverGroupEntities.forEach(
        age -> {
          approverGroups.add(convertEntityToApproverGroup(age));
        });
    team.setApproverGroups(approverGroups);

    // If the parameter is a password, do not return its value, for security reasons.
    if (team.getParameters() != null) {
      filterValueByFieldType(team.getParameters(), false, FieldType.PASSWORD.value());
    }

    return team;
  }

  /*
   * Set default quotas
   *
   * - Don't save the defaults against a team. Only retrieve dynamically.
   */
  private Quotas setDefaultQuotas() {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOW_COUNT)
                .getValue()));
    quotas.setMaxWorkflowRunMonthly(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_MONTHLY)
                .getValue()));
    quotas.setMaxWorkflowStorage(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOW_STORAGE)
                .getValue()
                .replace("Gi", "")));
    quotas.setMaxWorkflowRunStorage(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_STORAGE)
                .getValue()
                .replace("Gi", "")));
    quotas.setMaxWorkflowRunDuration(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_DURATION)
                .getValue()));
    quotas.setMaxConcurrentRuns(
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_CONCURRENT)
                .getValue()));
    return quotas;
  }

  /*
   * Sets the custom quotes only for whats provided.
   *
   * - Only store the set quotas on a team, so as not to override the defaults (which are retrieved
   * dynamically)
   */
  private void setCustomQuotas(Quotas quotas, Quotas customQuotas) {
    if (customQuotas != null) {
      if (customQuotas.getMaxWorkflowCount() != null) {
        quotas.setMaxWorkflowCount(customQuotas.getMaxWorkflowCount());
      }
      if (customQuotas.getMaxWorkflowRunMonthly() != null) {
        quotas.setMaxWorkflowRunMonthly(customQuotas.getMaxWorkflowRunMonthly());
      }
      if (customQuotas.getMaxWorkflowStorage() != null) {
        quotas.setMaxWorkflowStorage(customQuotas.getMaxWorkflowStorage());
      }
      if (customQuotas.getMaxWorkflowRunStorage() != null) {
        quotas.setMaxWorkflowRunStorage(customQuotas.getMaxWorkflowRunStorage());
      }
      if (customQuotas.getMaxWorkflowRunDuration() != null) {
        quotas.setMaxWorkflowRunDuration(customQuotas.getMaxWorkflowRunDuration());
      }
      if (customQuotas.getMaxConcurrentRuns() != null) {
        quotas.setMaxConcurrentRuns(customQuotas.getMaxConcurrentRuns());
      }
    }
  }

  public Integer getWorkflowMaxDurationForTeam(String team) {
    Integer d =
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_DURATION)
                .getValue());

    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (optWorkspaceEntity.isPresent()
        && optWorkspaceEntity.get().getQuotas() != null
        && optWorkspaceEntity.get().getQuotas().getMaxWorkflowRunDuration() != null
        && optWorkspaceEntity.get().getQuotas().getMaxWorkflowRunDuration() != 0) {
      d = optWorkspaceEntity.get().getQuotas().getMaxWorkflowRunDuration();
    }
    return d;
  }

  private CurrentQuotas setCurrentQuotas(CurrentQuotas currentQuotas, String team) {
    // Set Quota Reset Date
    Calendar nextMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    nextMonth.add(Calendar.MONTH, 1);
    nextMonth.set(Calendar.DAY_OF_MONTH, 1);
    nextMonth.set(Calendar.HOUR_OF_DAY, 0);
    nextMonth.set(Calendar.MINUTE, 0);
    nextMonth.set(Calendar.SECOND, 0);
    nextMonth.set(Calendar.MILLISECOND, 0);
    currentQuotas.setMonthlyResetDate(nextMonth.getTime());

    Calendar currentMonthStart = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    currentMonthStart.set(Calendar.DAY_OF_MONTH, 1);
    currentMonthStart.set(Calendar.HOUR_OF_DAY, 0);
    currentMonthStart.set(Calendar.MINUTE, 0);
    currentMonthStart.set(Calendar.SECOND, 0);
    currentMonthStart.set(Calendar.MILLISECOND, 0);

    Calendar currentMonthEnd = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    currentMonthEnd.set(Calendar.DAY_OF_MONTH, 1);
    currentMonthEnd.set(Calendar.HOUR_OF_DAY, 0);
    currentMonthEnd.set(Calendar.MINUTE, 0);
    currentMonthEnd.set(Calendar.SECOND, 0);
    currentMonthEnd.set(Calendar.MILLISECOND, 0);
    currentMonthEnd.add(Calendar.MONTH, 1);
    currentMonthEnd.add(Calendar.DAY_OF_MONTH, -1);

    WorkflowRunInsight insight =
        insightsService.get(
            team,
            currentMonthStart.getTime(),
            currentMonthEnd.getTime(),
            Optional.empty(),
            Optional.empty());
    LOGGER.debug("Insights: {}", insight.toString());
    currentQuotas.setCurrentConcurrentRuns(insight.getConcurrentRuns().intValue());
    currentQuotas.setCurrentRunTotalDuration(insight.getTotalDuration().intValue());
    currentQuotas.setCurrentRunMedianDuration(insight.getMedianDuration().intValue());
    currentQuotas.setCurrentRuns(insight.getTotalRuns().intValue());

    WorkflowCount count =
        workspaceWorkflowService.count(
            team, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    if (count.getStatus() != null) {
      Long active = count.getStatus().get("active");
      Long inactive = count.getStatus().get("inactive");
      currentQuotas.setCurrentWorkflowCount((int) (active + inactive));
    } else {
      currentQuotas.setCurrentWorkflowCount(0);
    }
    return currentQuotas;
  }

  /*
   * Helper method to convert from ApproverGroup Entity to Model
   */
  private ApproverGroup convertEntityToApproverGroup(ApproverGroupEntity age) {
    ApproverGroup ag = new ApproverGroup(age);
    if (!age.getApprovers().isEmpty()) {
      age.getApprovers()
          .forEach(
              ref -> {
                Optional<User> ue = userService.getUserByID(ref);
                if (ue.isPresent()) {
                  WorkspaceMember u = new WorkspaceMember(ue.get());
                  ag.getApprovers().add(u);
                }
              });
    }
    return ag;
  }

  /*
   * Returns the List of UserSummary for a team
   */
  private List<WorkspaceMember> getUsersForTeam(String team) {
    Map<String, String> memberRoleMap = relationshipService.membersAndRoles(team);
    List<WorkspaceMember> teamUsers = new LinkedList<>();
    if (!memberRoleMap.isEmpty()) {
      memberRoleMap.forEach(
          (m, r) -> {
            Optional<User> ue = userService.getUserByID(m);
            if (ue.isPresent()) {
              String role = RoleEnum.READER.getLabel();
              if (!r.isEmpty()) {
                role = r;
              }
              WorkspaceMember u = new WorkspaceMember(ue.get(), role);
              teamUsers.add(u);
            }
          });
    }
    return teamUsers;
  }

  /*
   * Creates a Relationship between User(s) and a Workspace
   * If relationship already exists, patch the role.
   * If user does not exist, a user record will be created with a relationship to the team
   */
  private void createOrUpdateUserRelationships(String team, List<WorkspaceMember> users) {
    if (users != null && !users.isEmpty()) {
      for (WorkspaceMember userSummary : users) {
        Optional<User> userEntity = Optional.empty();
        // Find user by ID or Email - UI allows adding from existing or new (email)
        if (userSummary.getId() != null && !userSummary.getId().isEmpty()) {
          userEntity = userService.getUserByID(userSummary.getId());
        } else if (userSummary.getEmail() != null && !userSummary.getEmail().isEmpty()) {
          userEntity = userService.getUserByEmail(userSummary.getEmail());
        }
        if (!userEntity.isPresent()) {
          // Create new user record & relationship
          // If user can't be created, will ignore and continue
          // TODO - invite the user rather than create a relationship
          Optional<UserEntity> newUser =
              userService.getAndRegisterUser(
                  userSummary.getEmail(),
                  null,
                  Optional.of(UserType.user),
                  Optional.of(UserStatus.inactive),
                  true);
          userEntity = userService.getUserByID(newUser.get().getId());
        }
        // Check the provided role is valid in our system
        if (RoleEnum.hasLabel(userSummary.getRole())) {
          relationshipService.createEdge(
              RelationshipType.USER,
              userEntity.get().getId(),
              RelationshipLabel.MEMBER_OF,
              RelationshipType.TEAM,
              team,
              Optional.of(Map.of("role", userSummary.getRole())));
        } else {
          throw new BoomerangException(BoomerangError.TEAM_INVALID_USER_ROLE);
        }
      }
    }
  }
}
