package io.boomerang.workspace;

import static io.boomerang.common.util.DataAdapterUtil.filterValueByFieldType;
import static io.boomerang.common.util.DataAdapterUtil.keepParamSpecValueByFieldType;

import io.boomerang.common.util.PagingUtil;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.util.StringUtil;
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
import io.boomerang.core.security.FlowWorkspaceProperties;
import io.boomerang.core.security.IdentityService;
import io.boomerang.workspace.entity.ApproverGroupEntity;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.ApproverGroup;
import io.boomerang.workspace.model.ApproverGroupRequest;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceNameCheckRequest;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceStatus;
import io.boomerang.workspace.model.WorkspaceSummary;
import io.boomerang.workspace.model.WorkspaceSummaryInsights;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import io.boomerang.workspace.repository.WorkspaceRepository;
import io.boomerang.workflow.QuotaService;
import io.boomerang.workflow.TaskService;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final List<String> RESERVED_WORKSPACE_NAMES =
      List.of("home", "admin", "system", "profile", "connect");
  private final WorkspaceRepository workspaceRepository;
  private final IdentityService identityService;
  private final UserService userService;
  private final ApproverGroupRepository approverGroupRepository;
  private final RoleRepository roleRepository;
  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final WorkflowService workflowService;
  private final TokenService tokenService;
  private final TaskService taskService;
  private final QuotaService quotaService;
  private final ParamLayerCache paramLayerCache;
  // flow.workspace.single: the seeded system workspace is the only one, so none is created or deleted.
  private final boolean single;

  public WorkspaceService(
      WorkspaceRepository workspaceRepository,
      IdentityService identityService,
      UserService userService,
      ApproverGroupRepository approverGroupRepository,
      RoleRepository roleRepository,
      SettingsService settingsService,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      WorkflowService workflowService,
      TokenService tokenService,
      TaskService taskService,
      QuotaService quotaService,
      ParamLayerCache paramLayerCache,
      Environment environment) {
    this.workspaceRepository = workspaceRepository;
    this.identityService = identityService;
    this.userService = userService;
    this.approverGroupRepository = approverGroupRepository;
    this.roleRepository = roleRepository;
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.workflowService = workflowService;
    this.tokenService = tokenService;
    this.taskService = taskService;
    this.quotaService = quotaService;
    this.paramLayerCache = paramLayerCache;
    this.single = FlowWorkspaceProperties.isSingle(environment);
  }

  /*
   * Validate the team name - used by the UI to determine if a team can be created
   */
  public ResponseEntity<?> validateName(WorkspaceNameCheckRequest request) {
    if (request.getName() != null && !request.getName().isBlank()) {
      String kebabName = StringUtil.kebabCase(request.getName());

      // Ensures unique team name (slug)
      if (relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, kebabName)
          || RESERVED_WORKSPACE_NAMES.contains(kebabName)) {
        throw new BoomerangException(BoomerangError.TEAM_NON_UNIQUE_NAME);
      }
      return ResponseEntity.ok().build();
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
  }

  /*
   * Retrieve a single team
   */
  public Workspace get(String workspace) {
    if (!Objects.isNull(workspace) && !workspace.isBlank()) {
      if (relationshipService.check(
          RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
        Optional<WorkspaceEntity> entity = workspaceRepository.findByNameIgnoreCase(workspace);
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
   * - Refused on a single-workspace instance
   */
  public Workspace create(WorkspaceRequest request) {
    if (single) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
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
          request,
          workspaceEntity,
          "id",
          "status",
          "members",
          "quotas",
          "parameters",
          "approverGroups",
          "labels");
      // labels is excluded above because WorkspaceRequest.labels defaults to null (so patch can
      // tell "omitted" from "clear all") - copyProperties would otherwise null out the entity's
      // default empty map on create.
      if (request.getLabels() != null) {
        workspaceEntity.setLabels(request.getLabels());
      }

      // Set custom quotas
      // Don't set default quotas as they can change over time and should be dynamic
      Quotas quotas = new Quotas();
      // Override quotas based on creation request
      QuotaService.overlay(quotas, request.getQuotas());
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
      paramLayerCache.evictAll();
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspaceEntity.getId(),
          workspaceEntity.getName(),
          Optional.empty(),
          Optional.empty());

      // Create Member Relationships. The creating USER is always a member - a session caller
      // who omits members must not create a workspace they then cannot see (the relationship
      // filter anchors at the user and returns nothing). Machine tokens (key/global) resolve no
      // user and skip the self-add, exactly as before.
      List<WorkspaceMember> members =
          new ArrayList<>(request.getMembers() != null ? request.getMembers() : List.of());
      UserEntity creator = userService.getCurrentUser();
      if (creator != null
          && members.stream().noneMatch(m -> creator.getId().equals(m.getId()))) {
        WorkspaceMember self = new WorkspaceMember();
        self.setId(creator.getId());
        self.setEmail(creator.getEmail());
        // Owner, matching the webapp's own self-include (Workspaces.tsx role: Owner).
        self.setRole(RoleEnum.OWNER.getLabel());
        members.add(self);
      }
      createOrUpdateUserRelationships(workspaceEntity.getName(), members);

      return convertWorkspaceEntityToWorkspace(workspaceEntity);
    } else {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
  }

  /*
   * Patch team
   */
  public Workspace patch(String workspace, WorkspaceRequest request) {
    if (request != null) {
      LOGGER.debug("[{}] Patch request received.", workspace);
      if (workspace == null || workspace.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
      }
      if (!relationshipService.check(
          RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
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
      if (request.getType() != null) {
        workspaceEntity.setType(request.getType());
      }
      if (request.getExternalRef() != null && !request.getExternalRef().isBlank()) {
        workspaceEntity.setExternalRef(request.getExternalRef());
      }
      // Whole-map replace, not merge: the caller sends the complete desired label set, so a
      // present (even empty) map fully replaces what's stored; only an absent map is a no-op.
      if (request.getLabels() != null) {
        workspaceEntity.setLabels(request.getLabels());
      }

      // Field-by-field merge onto the stored quotas: a quota the request does not carry keeps its
      // value (absent = inherit the platform default, 0 = unlimited), so setting one quota never
      // resets the others. Clearing one back to "inherit" is the quotas DELETE route.
      Quotas quotas =
          (workspaceEntity.getQuotas() != null) ? workspaceEntity.getQuotas() : new Quotas();
      QuotaService.overlay(quotas, request.getQuotas());
      workspaceEntity.setQuotas(quotas);

      // Create / Update Parameters
      if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        LOGGER.debug(
            "Request Parameters: {}", ParameterUtil.abstractParamNames(request.getParameters()));
        workspaceEntity.setParameters(
            createOrUpdateParameters(workspaceEntity.getParameters(), request.getParameters()));
      }

      // Create / Update ApproverGroups
      if (request.getApproverGroups() != null && !request.getApproverGroups().isEmpty()) {
        createOrUpdateApproverGroups(workspaceEntity, request.getApproverGroups());
      }

      workspaceRepository.save(workspaceEntity);
      paramLayerCache.evictAll();

      // Update any existing relationships if the name has changed
      if (updatedName) {
        relationshipService.updateNodeByRefOrSlug(
            RelationshipType.WORKSPACE, originalName, request.getName());
      }

      // Create / Update Relationships for Users
      createOrUpdateUserRelationships(workspaceEntity.getName(), request.getMembers());
      return convertWorkspaceEntityToWorkspace(workspaceEntity);
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
  }

  /*
   * Destructive cascade Workspace deletion. Refused on a single-workspace instance.
   */
  public void delete(String workspace) {
    if (single || workspace == null || workspace.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }

    // If no relationship, user has no access or team doesn't exist
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }

    // Get and delete all Workflows (this cascade deletes the Workflows, WorkflowRevisions,
    // Schedules, Actions, WorkflowRuns, and TaskRuns
    List<String> workflowRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.empty(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)));
    LOGGER.debug("Workspace Workflow Refs: {}", workflowRefs.toString());
    if (workflowRefs.size() > 0) {
      workflowRefs.forEach(ref -> workflowService.delete(workspace, ref));
    }

    // Delete all Tokens
    tokenService.deleteAllForPrincipal(workspace);

    // Delete all Workspace Tasks. TEAMTASK is the relationship type that anchors a task under
    // a workspace - TASK anchors the global catalogue under root and would never match here.
    List<String> templateNames =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            Optional.empty(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)));
    if (templateNames.size() > 0) {
      templateNames.forEach(name -> taskService.delete(workspace, name));
    }

    // TODO - Delete Workspace Integration Installations

    // Delete Workspace
    workspaceRepository.deleteByName(workspace);

    // Delete Workspace relationship node
    relationshipService.removeNodeAndEdgeByRefOrSlug(RelationshipType.WORKSPACE, workspace);
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
      Optional<String> querySearch,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryTeams) {
    List<String> teamRefs = new LinkedList<>();
    teamRefs =
        relationshipService.filter(
            RelationshipType.WORKSPACE, queryTeams, Optional.empty(), Optional.empty(), true);
    LOGGER.debug("TeamRefs: " + teamRefs.toString());

    return findByCriteria(
        queryPage, queryLimit, queryOrder, querySort, querySearch, queryLabels, queryStatus, teamRefs);
  }

  private Page<Workspace> findByCriteria(
      Optional<Integer> queryPage,
      Optional<Integer> queryLimit,
      Optional<Direction> queryOrder,
      Optional<String> querySort,
      Optional<String> querySearch,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      List<String> teamRefs) {
    final Sort sort =
        Sort.by(new Order(queryOrder.orElse(Direction.ASC), querySort.orElse("name")));

    QueryCriteria criteria = new QueryCriteria();
    if (querySearch.isPresent() && !querySearch.get().isBlank()) {
      // Anchored prefix match (not an unanchored `.*term.*` scan) so a future index on name /
      // displayName can actually serve this - no such index exists yet on the workspaces
      // collection today, so this still executes as a collection scan until one is added.
      String escapedSearch = Pattern.quote(querySearch.get().trim());
      criteria.add(
          new Criteria()
              .orOperator(
                  Criteria.where("name").regex("^" + escapedSearch, "i"),
                  Criteria.where("displayName").regex("^" + escapedSearch, "i")));
    }
    Query query =
        new Query(
            criteria
                .labels(queryLabels)
                .in("status", WorkspaceStatus.class, queryStatus, "status")
                .in("name", teamRefs)
                .build());
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);

    List<WorkspaceEntity> workspaceEntities = mongoTemplate.find(query, WorkspaceEntity.class);

    LOGGER.debug("Found " + workspaceEntities.size() + " teams.");
    List<Workspace> teams = new LinkedList<>();
    if (!workspaceEntities.isEmpty()) {
      workspaceEntities.forEach(workspaceEntity -> teams.add(convertWorkspaceEntityToWorkspace(workspaceEntity)));
    }

    Page<Workspace> pages =
        PagingUtil.page(teams, pageable, mongoTemplate, query, WorkspaceEntity.class);

    return pages;
  }

  public void removeMembers(String workspace, List<WorkspaceMember> request) {
    if (request != null && !request.isEmpty()) {
      if (workspace == null || workspace.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
      }
      if (!relationshipService.check(
          RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
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
                    RelationshipType.USER, userRef, RelationshipType.WORKSPACE, workspace));
      }
    }
  }

  /*
   *  Allows only the requesting user to leave the team
   *
   *  TODO: ensure the remaining owner cannot leave the team
   */
  public void leave(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    relationshipService.removeEdge(RelationshipType.WORKSPACE, workspace);
  }

  /*
   * Creates or Updates Workspace Parameters
   */
  private List<AbstractParam> createOrUpdateParameters(
      List<AbstractParam> parameters, List<AbstractParam> request) {
    if (!request.isEmpty()) {
      Map<String, Object> secretsBefore =
          SecretChangeHolder.secretValues("", parameters, FieldType.PASSWORD.value());
      LOGGER.debug("Starting Parameters: {}", ParameterUtil.abstractParamNames(parameters));
      // The one secret write rule: a secured value or default sent back as the redaction marker,
      // or not at all, carries the stored secret forward through the replacement below; a blank
      // clears it.
      keepParamSpecValueByFieldType(request, parameters, FieldType.PASSWORD.value());

      List<String> names = request.stream().map(AbstractParam::getName).toList();
      names.stream()
          .filter(name -> !ParameterUtil.isValidParamName(name))
          .findFirst()
          .ifPresent(
              name -> {
                throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
              });
      // A request param replaces the stored one of the same name in any case, as the keep step
      // above matches it.
      parameters =
          parameters.stream()
              .filter(p -> names.stream().noneMatch(name -> name.equalsIgnoreCase(p.getName())))
              .collect(Collectors.toList());

      // Add all new / updated params
      parameters.addAll(request);
      SecretChangeHolder.compare(
          secretsBefore,
          SecretChangeHolder.secretValues("", parameters, FieldType.PASSWORD.value()));
    }
    LOGGER.debug("Ending Parameters: {}", ParameterUtil.abstractParamNames(parameters));
    return parameters;
  }

  /*
   * Delete parameters by key
   */
  public void deleteParameter(String workspace, String name) {
    if (workspace == null || workspace.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
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
        paramLayerCache.evictAll();
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
        getApproverGroupsForWorkspace(workspaceEntity.getName());

    for (ApproverGroupRequest r : request) {
      // Ensure ApproverGroupName is not blank or null
      if (r.getName() == null || r.getName().isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
      }

      // Match by id when supplied so a rename updates the existing group; name matching is the
      // fallback for requests that carry no id.
      ApproverGroupEntity age =
          approverGroupEntities.stream()
              .filter(
                  e ->
                      (r.getId() != null && !r.getId().isBlank())
                          ? r.getId().equals(e.getId())
                          : e.getName().equalsIgnoreCase(r.getName()))
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
        }
        // Save outside the approvers guard so a rename without an approvers list still persists.
        approverGroupRepository.save(age);
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
            RelationshipType.WORKSPACE,
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

  // Retrieve ApproverGroups by relationship as they are stored separately to the WorkspaceEntity.
  // The relationship node's ref is the group id (its slug is the name), so ask for refs - the
  // repository lookup below is by id.
  private List<ApproverGroupEntity> getApproverGroupsForWorkspace(String workspace) {
    List<String> approverGroupRefs =
        relationshipService.refsInWorkspace(
            RelationshipType.APPROVERGROUP, workspace, Optional.empty());
    List<ApproverGroupEntity> approverGroupEntities =
        approverGroupRepository.findByIdIn(approverGroupRefs);
    return approverGroupEntities;
  }

  /*
   * Delete an Approver Group
   *
   * - Removes relationship as well
   */
  public void deleteApproverGroups(String workspace, List<String> request) {
    if (workspace == null || workspace.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
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
  public void deleteCustomQuotas(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REQ);
    }
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(workspace);
    if (!optWorkspaceEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();

    // Delete any custom quotas set on the team
    // This will then reset and default to the Quotas group in Settings
    workspaceEntity.setQuotas(new Quotas());
    workspaceRepository.save(workspaceEntity);
  }

  /*
   * The platform's default quotas, which a workspace without its own values inherits.
   */
  public ResponseEntity<Quotas> getDefaultQuotas() {
    return ResponseEntity.ok(quotaService.defaults());
  }

  /*
   * Builds the WorkspaceSummary (with Insights) list for the given team-ref -> role map,
   * skipping any ref that no longer resolves to an existing Workspace (stale relationship
   * entries).
   *
   * Used to compose the User Profile response (api layer) - the rollup needs both User
   * (core) and Workspace (workspace) data, so it can't live in core.UserService. Resolved
   * permissions for the profile come from TokenService.resolvePermissionsForUser - the same
   * source createSessionToken uses - not from here.
   */
  public List<WorkspaceSummary> getWorkspaceMembershipSummary(Map<String, String> teamRefsAndRoles) {
    List<WorkspaceSummary> teamSummaries = new LinkedList<>();
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
                    Optional.of(RelationshipType.WORKSPACE),
                    Optional.of(List.of(k)));
            tsi.setWorkflows(Long.valueOf(workflowRefs.size()));
            ts.setInsights(tsi);
            teamSummaries.add(ts);
          }
        });
    return teamSummaries;
  }

  /*
   * Return all team level roles
   */
  public ResponseEntity<List<Role>> getRoles() {
    List<RoleEntity> roleEntities = roleRepository.findByType("workspace");
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

    // Get Members
    team.setMembers(getUsersForWorkspace(workspaceEntity.getName()));

    // The workspace's limits and current usage
    team.setQuotas(quotaService.current(workspaceEntity));

    // Get Approver Groups
    List<ApproverGroupEntity> approverGroupEntities =
        getApproverGroupsForWorkspace(workspaceEntity.getName());
    List<ApproverGroup> approverGroups = new LinkedList<>();
    approverGroupEntities.forEach(
        age -> {
          approverGroups.add(convertEntityToApproverGroup(age));
        });
    team.setApproverGroups(approverGroups);

    // If the parameter is a password, do not return its value or default, for security reasons.
    if (team.getParameters() != null) {
      filterValueByFieldType(team.getParameters(), false, FieldType.PASSWORD.value());
      filterValueByFieldType(team.getParameters(), true, FieldType.PASSWORD.value());
    }

    return team;
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
  private List<WorkspaceMember> getUsersForWorkspace(String workspace) {
    Map<String, String> memberRoleMap = relationshipService.membersAndRoles(workspace);
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
  private void createOrUpdateUserRelationships(String workspace, List<WorkspaceMember> users) {
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
                  Optional.empty(),
                  Optional.of(UserType.user),
                  Optional.of(UserStatus.inactive),
                  true);
          // Honour the comment above: an unresolvable member (no email, e.g. the security-off
          // virtual user, or registration refused) is skipped, not a 500. Previously the raw
          // null Optional NPE'd inside getAndRegisterUser and the .get() threw on empty.
          if (newUser.isEmpty()) {
            LOGGER.warn(
                "Skipping workspace member with unresolvable user (id: {}, email: {})",
                userSummary.getId(),
                userSummary.getEmail());
            continue;
          }
          userEntity = userService.getUserByID(newUser.get().getId());
        }
        // Check the provided role is valid in our system
        if (RoleEnum.hasLabel(userSummary.getRole())) {
          relationshipService.createEdge(
              RelationshipType.USER,
              userEntity.get().getId(),
              RelationshipLabel.MEMBER_OF,
              RelationshipType.WORKSPACE,
              workspace,
              Optional.of(Map.of("role", userSummary.getRole())));
        } else {
          throw new BoomerangException(BoomerangError.TEAM_INVALID_USER_ROLE);
        }
      }
    }
  }
}
