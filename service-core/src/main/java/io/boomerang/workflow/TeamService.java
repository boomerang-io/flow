package io.boomerang.workflow;

import static io.boomerang.common.util.DataAdapterUtil.filterValueByFieldType;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.WorkflowCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
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
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.security.IdentityService;
import io.boomerang.workflow.entity.ApproverGroupEntity;
import io.boomerang.workflow.entity.TeamEntity;
import io.boomerang.workflow.model.ApproverGroup;
import io.boomerang.workflow.model.ApproverGroupRequest;
import io.boomerang.workflow.model.CurrentQuotas;
import io.boomerang.workflow.model.Quotas;
import io.boomerang.workflow.model.Team;
import io.boomerang.workflow.model.TeamMember;
import io.boomerang.workflow.model.TeamNameCheckRequest;
import io.boomerang.workflow.model.TeamRequest;
import io.boomerang.workflow.model.TeamStatus;
import io.boomerang.workflow.repository.ApproverGroupRepository;
import io.boomerang.workflow.repository.TeamRepository;
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

@Service
public class TeamService {

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

  private final TeamRepository teamRepository;
  private final IdentityService identityService;
  private final UserService userService;
  private final ApproverGroupRepository approverGroupRepository;
  private final RoleRepository roleRepository;
  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final InsightsService insightsService;
  private final WorkflowService workflowService;
  private final TokenService tokenService;
  private final TaskService taskTemplateService;

  public TeamService(
      TeamRepository teamRepository,
      IdentityService identityService,
      UserService userService,
      ApproverGroupRepository approverGroupRepository,
      RoleRepository roleRepository,
      SettingsService settingsService,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      InsightsService insightsService,
      WorkflowService workflowService,
      TokenService tokenService,
      TaskService taskTemplateService) {
    this.teamRepository = teamRepository;
    this.identityService = identityService;
    this.userService = userService;
    this.approverGroupRepository = approverGroupRepository;
    this.roleRepository = roleRepository;
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.insightsService = insightsService;
    this.workflowService = workflowService;
    this.tokenService = tokenService;
    this.taskTemplateService = taskTemplateService;
  }

  /*
   * Validate the team name - used by the UI to determine if a team can be created
   */
  public ResponseEntity<?> validateName(TeamNameCheckRequest request) {
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
  public Team get(String team) {
    if (!Objects.isNull(team) && !team.isBlank()) {
      if (relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        Optional<TeamEntity> entity = teamRepository.findByNameIgnoreCase(team);
        if (entity.isPresent()) {
          return convertTeamEntityToTeam(entity.get());
        }
      }
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
  }

  /*
   * Creates a new Team
   *
   * - Name must not be blank
   * - Display name must not be blank
   */
  public Team create(TeamRequest request) {
    if (!request.getName().isBlank() && !request.getDisplayName().isBlank()) {
      // Validate name - will throw exception if not valid
      TeamNameCheckRequest checkRequest = new TeamNameCheckRequest(request.getName());
      this.validateName(checkRequest);

      /*
       * Create TeamEntity & Copy majority of fields.
       * - Status is ignored - can only be active
       * - Members, quotas, parameters, and approverGroups need further logic
       */
      TeamEntity teamEntity = new TeamEntity();
      BeanUtils.copyProperties(
          request, teamEntity, "id", "status", "members", "quotas", "parameters", "approverGroups");

      // Set custom quotas
      // Don't set default quotas as they can change over time and should be dynamic
      Quotas quotas = new Quotas();
      // Override quotas based on creation request
      setCustomQuotas(quotas, request.getQuotas());
      teamEntity.setQuotas(quotas);

      // Create / Update Parameters
      if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        teamEntity.setParameters(
            createOrUpdateParameters(teamEntity.getParameters(), request.getParameters()));
      }

      // Create / Update ApproverGroups
      if (request.getApproverGroups() != null && !request.getApproverGroups().isEmpty()) {
        createOrUpdateApproverGroups(teamEntity, request.getApproverGroups());
      }

      teamEntity = teamRepository.save(teamEntity);
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.TEAM,
          teamEntity.getId(),
          teamEntity.getName(),
          Optional.empty(),
          Optional.empty());

      // Create Member Relationships
      createOrUpdateUserRelationships(teamEntity.getName(), request.getMembers());

      return convertTeamEntityToTeam(teamEntity);
    } else {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
  }

  /*
   * Patch team
   */
  public Team patch(String team, TeamRequest request) {
    if (request != null) {
      LOGGER.debug("Request: " + request.toString());
      if (team == null || team.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      if (!relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
      if (!optTeamEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      TeamEntity teamEntity = optTeamEntity.get();
      boolean updatedName = false;
      String originalName = teamEntity.getName();
      if (request.getName() != null && !request.getName().isBlank()) {
        teamEntity.setName(request.getName());
        updatedName = true;
      }
      if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
        teamEntity.setDisplayName(request.getDisplayName());
      }
      if (request.getStatus() != null) {
        teamEntity.setStatus(request.getStatus());
      }
      if (request.getExternalRef() != null && !request.getExternalRef().isBlank()) {
        teamEntity.setExternalRef(request.getExternalRef());
      }
      if (request.getLabels() != null && !request.getLabels().isEmpty()) {
        teamEntity.getLabels().putAll(request.getLabels());
      }

      // Set custom quotas
      // Don't set default quotas as they can change over time and should be dynamic
      Quotas quotas = new Quotas();
      // Override quotas based on creation request
      setCustomQuotas(quotas, request.getQuotas());
      teamEntity.setQuotas(quotas);

      // Create / Update Parameters
      if (request.getParameters() != null && !request.getParameters().isEmpty()) {
        LOGGER.debug("Request Parameters: " + request.getParameters().toString());
        teamEntity.setParameters(
            createOrUpdateParameters(teamEntity.getParameters(), request.getParameters()));
      }

      // Create / Update ApproverGroups
      if (request.getApproverGroups() != null && !request.getApproverGroups().isEmpty()) {
        createOrUpdateApproverGroups(teamEntity, request.getApproverGroups());
      }

      teamRepository.save(teamEntity);

      // Update any existing relationships if the name has changed
      if (updatedName) {
        relationshipService.updateNodeByRefOrSlug(
            RelationshipType.TEAM, originalName, request.getName());
      }

      // Create / Update Relationships for Users
      createOrUpdateUserRelationships(teamEntity.getName(), request.getMembers());
      return convertTeamEntityToTeam(teamEntity);
    }
    throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
  }

  /*
   * Destructive cascade Team deletion
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
    LOGGER.debug("Team Workflow Refs: {}", workflowRefs.toString());
    if (workflowRefs.size() > 0) {
      workflowRefs.forEach(ref -> workflowService.delete(team, ref));
    }

    // Delete all Tokens
    tokenService.deleteAllForPrincipal(team);

    // Delete all Team Tasks
    List<String> templateRefs =
        relationshipService.filter(
            RelationshipType.TASK,
            Optional.empty(),
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)));
    if (templateRefs.size() > 0) {
      templateRefs.forEach(ref -> taskTemplateService.delete(ref, team));
    }

    // TODO - Delete Team Integration Installations

    // Delete Team
    teamRepository.deleteByName(team);

    // Delete Team relationship node
    relationshipService.removeNodeAndEdgeByRefOrSlug(RelationshipType.TEAM, team);
  }

  /*
   * Query for Teams
   *
   * Returns Teams plus each Teams UserRefs, WorkflowRefs, and Quotas
   */
  public Page<Team> query(
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

  private Page<Team> findByCriteria(
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
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(TeamStatus.class, q))) {
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

    List<TeamEntity> teamEntities = mongoTemplate.find(query, TeamEntity.class);

    LOGGER.debug("Found " + teamEntities.size() + " teams.");
    List<Team> teams = new LinkedList<>();
    if (!teamEntities.isEmpty()) {
      teamEntities.forEach(teamEntity -> teams.add(convertTeamEntityToTeam(teamEntity)));
    }

    Page<Team> pages =
        PageableExecutionUtils.getPage(
            teams, pageable, () -> mongoTemplate.count(query, TeamEntity.class));

    return pages;
  }

  public void removeMembers(String team, List<TeamMember> request) {
    if (request != null && !request.isEmpty()) {
      if (team == null || team.isBlank()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      if (!relationshipService.check(
          RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
      if (!optTeamEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
      }
      List<String> userRefs = new LinkedList<>();
      for (TeamMember userSummary : request) {
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
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (!optTeamEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    relationshipService.removeEdge(RelationshipType.TEAM, team);
  }

  /*
   * Creates or Updates Team Parameters
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
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (!optTeamEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    TeamEntity teamEntity = optTeamEntity.get();

    if (teamEntity.getParameters() != null) {
      List<AbstractParam> parameters = teamEntity.getParameters();
      Optional<AbstractParam> optionalParameter =
          parameters.stream().filter(p -> p.getName().equals(name)).findAny();
      if (optionalParameter.isPresent()) {
        parameters.remove(optionalParameter.get());
        teamEntity.setParameters(parameters);
        teamRepository.save(teamEntity);
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
      TeamEntity teamEntity, List<ApproverGroupRequest> request) {
    List<ApproverGroupEntity> approverGroupEntities =
        getApproverGroupsForTeam(teamEntity.getName());

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
              relationshipService.membersAndRoles(teamEntity.getName());
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
              relationshipService.membersAndRoles(teamEntity.getName());
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
            teamEntity.getId(),
            RelationshipLabel.HAS_APPROVER_GROUP,
            RelationshipType.APPROVERGROUP,
            approverGroupEntity.getId(),
            approverGroupEntity.getName(),
            Optional.empty(),
            Optional.empty());
      }
    }
  }

  // Retrieve ApproverGroups by relationship as they are stored separately to the TeamEntity
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
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (!optTeamEntity.isPresent()) {
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
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (!optTeamEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    TeamEntity teamEntity = optTeamEntity.get();

    // Delete any custom quotas set on the team
    // This will then reset and default to the Team Quotas set in Settings
    teamEntity.setQuotas(new Quotas());
    teamRepository.save(teamEntity);
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
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (optTeamEntity.isPresent()) {
      Quotas quotas = setDefaultQuotas();
      setCustomQuotas(quotas, optTeamEntity.get().getQuotas());
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
   * Converts the Team Entity to Model and adds the extra Users, WorkflowRefs, ApproverGroupRefs,
   * Quotas
   */
  private Team convertTeamEntityToTeam(TeamEntity teamEntity) {
    Team team = new Team(teamEntity);

    //    List<WorkflowSummary> summary = new LinkedList<>();
    //    try {
    //      WorkflowResponsePage response = workflowService.query(Optional.empty(),
    // Optional.empty(), Optional.of(Direction.ASC), Optional.empty(), Optional.empty(),
    // Optional.of(List.of(teamEntity.getId())), Optional.empty());
    //      if (response.getContent() != null && !response.getContent().isEmpty()) {
    //        List<Workflow> workflows = response.getContent();
    //        workflows.forEach(w -> summary.add(new WorkflowSummary(w)));
    //      }
    //    } catch (BoomerangException e) {
    //      LOGGER.error("convertTeamEntityToTeam() - issue in retrieving Workflows for this team.
    // Most likely cause is page size is being returned as 0");
    //    }
    //    team.setWorkflows(summary);

    // Get Members
    team.setMembers(getUsersForTeam(teamEntity.getName()));

    // Get default & custom stored Quotas
    Quotas quotas = setDefaultQuotas();
    setCustomQuotas(quotas, teamEntity.getQuotas());
    CurrentQuotas currentQuotas = new CurrentQuotas(quotas);
    setCurrentQuotas(currentQuotas, teamEntity.getName());
    team.setQuotas(currentQuotas);

    // Get Approver Groups
    List<ApproverGroupEntity> approverGroupEntities =
        getApproverGroupsForTeam(teamEntity.getName());
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

  protected Integer getWorkflowMaxDurationForTeam(String team) {
    Integer d =
        Integer.valueOf(
            settingsService
                .getSettingConfig(TEAMS_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_DURATION)
                .getValue());

    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (optTeamEntity.isPresent()
        && optTeamEntity.get().getQuotas() != null
        && optTeamEntity.get().getQuotas().getMaxWorkflowRunDuration() != null
        && optTeamEntity.get().getQuotas().getMaxWorkflowRunDuration() != 0) {
      d = optTeamEntity.get().getQuotas().getMaxWorkflowRunDuration();
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
        workflowService.count(
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
                  TeamMember u = new TeamMember(ue.get());
                  ag.getApprovers().add(u);
                }
              });
    }
    return ag;
  }

  /*
   * Returns the List of UserSummary for a team
   */
  private List<TeamMember> getUsersForTeam(String team) {
    Map<String, String> memberRoleMap = relationshipService.membersAndRoles(team);
    List<TeamMember> teamUsers = new LinkedList<>();
    if (!memberRoleMap.isEmpty()) {
      memberRoleMap.forEach(
          (m, r) -> {
            Optional<User> ue = userService.getUserByID(m);
            if (ue.isPresent()) {
              String role = RoleEnum.READER.getLabel();
              if (!r.isEmpty()) {
                role = r;
              }
              TeamMember u = new TeamMember(ue.get(), role);
              teamUsers.add(u);
            }
          });
    }
    return teamUsers;
  }

  /*
   * Creates a Relationship between User(s) and a Team
   * If relationship already exists, patch the role.
   * If user does not exist, a user record will be created with a relationship to the team
   */
  private void createOrUpdateUserRelationships(String team, List<TeamMember> users) {
    if (users != null && !users.isEmpty()) {
      for (TeamMember userSummary : users) {
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
