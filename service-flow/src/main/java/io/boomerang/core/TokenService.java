package io.boomerang.core;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.RoleEnum;
import io.boomerang.core.enums.TokenTypePrefix;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.*;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionResource;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class TokenService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String TOKEN_PERMISSION_REGEX =
      "(\\*{2}|[0-9a-zA-Z\\-]+)\\/(\\*{2}|read|write|action|delete){1}";

  @Value("${flow.token.max-user-session-duration}")
  private Integer MAX_SESSION_TOKEN_DURATION;

  private final TokenRepository tokenRepository;
  private final UserService userService;
  private final RoleRepository roleRepository;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;

  public TokenService(
      TokenRepository tokenRepository,
      UserService userService,
      RoleRepository roleRepository,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate) {
    this.tokenRepository = tokenRepository;
    this.userService = userService;
    this.roleRepository = roleRepository;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
  }

  /*
   * Creates an Access Token
   *
   * Limited to creation by a User on behalf of a User, Workflow, Team, Global scope
   *
   * TODO: make sure requesting principal has access to create for the provided principal
   */
  public TokenCreateResponse create(TokenCreateRequest request) {
    // Disallow creation of session tokens except via internal AuthenticationFilter
    if (AuthScope.session.equals(request.getType())) {
      throw new BoomerangException(BoomerangError.TOKEN_INVALID_SESSION_REQ);
    }

    // Required field checks
    // - Type and name: required for all tokens
    // - Principal: required if type!=global
    // - Permissions: required for type!=user
    if (request.getType() == null
        || request.getName() == null
        || request.getName().isEmpty()
        || (!AuthScope.global.equals(request.getType())
            && (request.getPrincipal() == null || request.getPrincipal().isBlank()))
        || (!AuthScope.user.equals(request.getType())
            && (request.getPermissions() == null || request.getPermissions().isEmpty()))) {
      throw new BoomerangException(BoomerangError.TOKEN_INVALID_REQ);
    }

    // Validate permissions matches the REGEX
    if (!AuthScope.user.equals(request.getType())) {
      request
          .getPermissions()
          .forEach(
              p -> {
                if (!p.matches(TOKEN_PERMISSION_REGEX)) {
                  throw new BoomerangException(BoomerangError.TOKEN_INVALID_PERMISSION);
                }
                String[] pSplit = p.split("/");
                LOGGER.debug("Scope: " + PermissionResource.valueOfLabel(pSplit[0]));
                if (PermissionResource.valueOfLabel(pSplit[0]) == null) {
                  throw new BoomerangException(BoomerangError.TOKEN_INVALID_PERMISSION);
                }
                LOGGER.debug("Action: " + pSplit[1]);
                // ACTION is already checked as part of the regex
              });
    }

    // Create TokenEntity
    TokenEntity tokenEntity = new TokenEntity();
    tokenEntity.setType(request.getType());
    tokenEntity.setName(request.getName());
    tokenEntity.setDescription(request.getDescription());
    tokenEntity.setExpirationDate(request.getExpirationDate());
    if (!AuthScope.global.equals(request.getType())) {
      tokenEntity.setPrincipal(request.getPrincipal());
    }

    // Set Permissions
    if (AuthScope.user.equals(request.getType())) {
      // TODO do i need to check if user is Admin or Operator
      Map<String, String> teamsAndRoles = relationshipService.roles(request.getPrincipal());
      for (Map.Entry<String, String> entry : teamsAndRoles.entrySet()) {
        ResolvedPermissions resolvedPermissions =
            new ResolvedPermissions(
                AuthScope.team,
                entry.getKey(),
                roleRepository.findByTypeAndName("team", entry.getValue()).getPermissions());
        tokenEntity.getPermissions().add(resolvedPermissions);
      }
    } else {
      tokenEntity
          .getPermissions()
          .add(
              new ResolvedPermissions(
                  request.getType(),
                  request.getPrincipal() != null && !request.getPrincipal().isBlank()
                      ? request.getPrincipal()
                      : "**",
                  request.getPermissions()));
    }
    LOGGER.debug(tokenEntity.getPermissions().toString());

    String prefix = TokenTypePrefix.valueOf(request.getType().toString()).getPrefix();
    String uniqueToken = prefix + "_" + UUID.randomUUID().toString().toLowerCase();

    final String hashToken = hashString(uniqueToken);
    LOGGER.debug("Token: " + uniqueToken);
    tokenEntity.setToken(hashToken);
    tokenEntity = tokenRepository.save(tokenEntity);

    // Create an Audit relationship
    // relationshipService.addRelationshipRef(RelationshipRef.USER,
    // identityService.getCurrentUser().getId(),
    // RelationshipType.CREATED, RelationshipRef.TOKEN, Optional.of(tokenEntity.getId()));

    TokenCreateResponse tokenResponse = new TokenCreateResponse();
    tokenResponse.setToken(uniqueToken);
    tokenResponse.setId(tokenEntity.getId());
    tokenResponse.setType(request.getType());
    tokenResponse.setExpirationDate(request.getExpirationDate());
    return tokenResponse;
  }

  public String hashString(String originalString) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(originalString.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte element : hash) {
        String hex = Integer.toHexString(0xff & element);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  public boolean validate(String token) {
    LOGGER.debug("Token Validation - token: " + token);
    String hash = hashString(token);
    LOGGER.debug("Token Validation - hash: " + hash);
    Optional<TokenEntity> tokenEntityOptional = this.tokenRepository.findByToken(hash);
    if (tokenEntityOptional.isPresent()) {
      TokenEntity tokenEntity = tokenEntityOptional.get();
      if (isValid(tokenEntity.getExpirationDate())) {
        LOGGER.debug("Token Validation - valid");
        return true;
      }
    }
    LOGGER.debug("Token Validation - not valid");
    return false;
  }

  public boolean delete(String id) {
    Optional<TokenEntity> tokenEntityOptional = this.tokenRepository.findById(id);
    if (tokenEntityOptional.isPresent()) {
      TokenEntity tokenEntity = tokenEntityOptional.get();
      this.tokenRepository.delete(tokenEntity);
      return true;
    }
    return false;
  }

  public void deleteAllForPrincipal(String principal) {
    this.tokenRepository.deleteAllByPrincipal(principal);
  }

  public Page<Token> query(
      Optional<Date> from,
      Optional<Date> to,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> queryOrder,
      Optional<String> querySort,
      Optional<List<AuthScope>> queryTypes,
      Optional<List<String>> queryPrincipals) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort =
        Sort.by(new Order(queryOrder.orElse(Direction.ASC), querySort.orElse("creationDate")));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }
    if (queryTypes.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("type").in(queryTypes.get());
      criteriaList.add(dynamicCriteria);
    }
    if (queryPrincipals.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("principal").in(queryPrincipals.get());
      criteriaList.add(dynamicCriteria);
    }

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

    List<TokenEntity> entities = mongoTemplate.find(query, TokenEntity.class);

    List<Token> response = new LinkedList<>();
    entities.forEach(
        te -> {
          Token token = new Token(te);
          token.setValid(isValid(te.getExpirationDate()));
          response.add(token);
        });

    Page<Token> pages =
        PageableExecutionUtils.getPage(
            response, pageable, () -> mongoTemplate.count(query, ActionEntity.class));

    return pages;
  }

  public Token get(String token) {
    String hash = hashString(token);
    Optional<TokenEntity> tokenEntityOptional = this.tokenRepository.findByToken(hash);
    if (tokenEntityOptional.isPresent()) {
      TokenEntity tokenEntity = tokenEntityOptional.get();
      Token response = new Token();
      response.setValid(isValid(tokenEntity.getExpirationDate()));
      BeanUtils.copyProperties(tokenEntity, response);
      return response;
    }
    return null;
  }

  /*
   * Token.valid element is only on the Model and not the Data Entity It is a derived element based
   * on expiration date
   */
  private boolean isValid(Date expirationDate) {
    Date currentDate = new Date();
    if (expirationDate == null || expirationDate.after(currentDate)) {
      return true;
    }
    return false;
  }

  /*
   * Creates a token expiring in MAX SESSION TIME. Used by the AuthenticationFilter when accessed by
   * non Access Token
   *
   * TODO: add scopes that a user session token would have (needs to based on User ... eventually
   * dynamic)
   *
   * TODO: is this method declaration ever call besides the wrapper - should they be combined.
   */
  public Token createSessionToken(
      String email,
      String firstName,
      String lastName,
      boolean allowActivation,
      boolean allowUserCreation) {
    Optional<UserEntity> user = Optional.empty();
    String name = String.format("%s %s", sanitise(firstName), sanitise(lastName));
    if (allowActivation && !userService.isActivated()) {
      user =
          userService.getAndRegisterUser(
              email,
              Optional.of(name),
              Optional.of(UserType.admin),
              Optional.empty(),
              allowUserCreation);
      if (user.isPresent()) {
        relationshipService.createEdge(
            RelationshipType.USER,
            user.get().getId(),
            RelationshipLabel.MEMBER_OF,
            RelationshipType.TEAM,
            "system",
            Optional.of(Map.of("role", RoleEnum.OWNER.getLabel())));
      }
    } else if (userService.isActivated()) {
      user =
          userService.getAndRegisterUser(
              email,
              Optional.of(name),
              Optional.of(UserType.user),
              Optional.empty(),
              allowUserCreation);
    } else {
      throw new HttpClientErrorException(HttpStatus.LOCKED);
    }
    if (!user.isPresent()) {
      throw new HttpClientErrorException(HttpStatus.FORBIDDEN);
    }
    Date expiryDate = getExpirationDate();

    TokenEntity tokenEntity = new TokenEntity();
    tokenEntity.setCreationDate(new Date());
    tokenEntity.setDescription("Generated User Session Token");
    tokenEntity.setType(AuthScope.session);
    tokenEntity.setExpirationDate(expiryDate);
    tokenEntity.setPrincipal(user.get().getId());
    List<String> permissions = new LinkedList<>();
    if (UserType.admin.equals(user.get().getType())
        || UserType.operator.equals(user.get().getType())) {
      tokenEntity
          .getPermissions()
          .add(
              new ResolvedPermissions(
                  AuthScope.global,
                  "**",
                  roleRepository
                      .findByTypeAndName("global", user.get().getType().toString())
                      .getPermissions()));
    } else {
      // Collect all team permissions the user has
      Map<String, String> teamsAndRoles = relationshipService.roles(user.get().getId());
      for (Map.Entry<String, String> entry : teamsAndRoles.entrySet()) {
        tokenEntity
            .getPermissions()
            .add(
                new ResolvedPermissions(
                    AuthScope.team,
                    entry.getKey(),
                    roleRepository.findByTypeAndName("team", entry.getValue()).getPermissions()));
      }
    }
    String prefix = TokenTypePrefix.session.prefix;
    String uniqueToken = prefix + "_" + UUID.randomUUID().toString().toLowerCase();

    final String hashToken = hashString(uniqueToken);
    tokenEntity.setToken(hashToken);
    tokenEntity = tokenRepository.save(tokenEntity);

    return new Token(tokenEntity);
  }

  private Date getExpirationDate() {
    Calendar cal = Calendar.getInstance();
    cal.setTime(new Date());
    cal.add(Calendar.HOUR, MAX_SESSION_TOKEN_DURATION);
    return cal.getTime();
  }

  private String sanitise(String value) {
    if (StringUtils.isBlank(value)) {
      return value;
    }
    String cleanString = value;
    try {
      cleanString = java.net.URLDecoder.decode(value, "UTF-8");
    } catch (final UnsupportedEncodingException e) {
      return value;
    }
    // Need to make lower as capitalize only converts the first letter to upper but doesn't change
    // the remaining to lower
    cleanString = cleanString.toLowerCase();
    cleanString = StringUtils.capitalize(cleanString);
    return cleanString;
  }

  /*
   * Creates a Workflow / System token for use by the scheduled task
   */
  public Token createWorkflowSchedulerToken(String workflowRef) {
    TokenCreateRequest tokenRequest = new TokenCreateRequest();
    tokenRequest.setName("scheduled-job-token");
    tokenRequest.setType(AuthScope.workflow);
    tokenRequest.setPrincipal(workflowRef);
    tokenRequest.setPermissions(List.of("workflow/**"));

    Calendar cal = Calendar.getInstance();
    cal.setTime(new Date());
    cal.add(Calendar.HOUR, 1);
    Date expiryDate = cal.getTime();
    tokenRequest.setExpirationDate(expiryDate);

    final TokenCreateResponse tokenResponse = this.create(tokenRequest);

    return this.get(tokenResponse.getToken());
  }
}
