package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.OneTimeCode;
import io.boomerang.core.model.UserRequest;
import io.boomerang.core.model.UserSettings;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.ActivationCode;
import io.boomerang.core.security.IdentityService;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers {@code UserService}'s "no principal" behaviour (e.g. {@code
 * flow.security.enabled=false}, where {@code IdentityService} resolves no principal at all): the
 * {@code /api/v2/profile} / {@code /api/v2/context} bootstrap calls the webapp makes on every
 * route must degrade to an anonymous/default answer instead of NPE-ing, while the identity-scoped
 * write path ({@code updateCurrentProfile}) must fail clearly rather than silently falling
 * through to an unauthenticated update-by-email.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private IdentityService identityService;
  @Mock private ExternalUserService extUserService;
  @Mock private RelationshipService relationshipService;
  @Mock private UserRepository userRepository;

  private static final String CODE = "right-code-right-code-right-code-0123";

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserService(
            mongoTemplate,
            identityService,
            extUserService,
            relationshipService,
            userRepository,
            JsonMapper.builder().build(),
            new ActivationCode(CODE, ""));
    // externalUserUrl blank = the internal (non-external-IdP) user store, the default posture.
    ReflectionTestUtils.setField(userService, "externalUserUrl", "");
  }

  /**
   * An identity is always established now, so "no principal" is gone. The reachable case is a
   * principal with no USER RECORD - the security-off {@code UnauthenticatedGlobalToken}
   * ({@code principal="system"}), and equally any {@code key}/{@code global} machine token with
   * security enabled. That used to throw {@code NoSuchElementException} out of {@code .get()}.
   */
  @Test
  void machinePrincipalWithNoUserRecordGetCurrentUserReturnsNullNotThrows() {
    when(identityService.getCurrentPrincipal()).thenReturn("system");
    when(userRepository.findById("system")).thenReturn(java.util.Optional.empty());

    assertThat(userService.getCurrentUser()).isNull();
  }

  @Test
  void resolvedPrincipalGetCurrentUserStillLooksUpTheUser() {
    UserEntity entity = new UserEntity();
    entity.setId("u1");
    when(identityService.getCurrentPrincipal()).thenReturn("u1");
    when(userRepository.findById("u1")).thenReturn(java.util.Optional.of(entity));

    assertThat(userService.getCurrentUser().getId()).isEqualTo("u1");
  }

  @Test
  void noPrincipalGetCurrentProfileEntityReturnsAnonymousDefaultNotNpe() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);

    UserEntity profile = userService.getCurrentProfileEntity();

    assertThat(profile).isNotNull();
    assertThat(profile.getId()).isNull();
  }

  @Test
  void noPrincipalUpdateCurrentProfileFailsClearlyRatherThanUpdatingByEmail() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);
    UserRequest request = new UserRequest();
    request.setEmail("someone-else@example.com");

    assertThatThrownBy(() -> userService.updateCurrentProfile(request))
        .asInstanceOf(InstanceOfAssertFactories.type(BoomerangException.class))
        .extracting(BoomerangException::getReason)
        .isEqualTo(BoomerangError.AUTH_REQUIRED.getReason());

    verify(userRepository, never()).findByEmailAndStatus(any(), any());
  }

  /**
   * A profile patch names only the settings it changes: closing Home's "Get started" row must not
   * reset the user's consent or their other settings to the defaults.
   */
  @Test
  void patchingOneSettingLeavesTheOthersAsStored() {
    io.boomerang.core.model.Token self =
        new io.boomerang.core.model.Token(io.boomerang.core.security.enums.AuthScope.session);
    self.setPrincipal("u1");
    when(identityService.getCurrentPrincipal()).thenReturn("u1");
    when(identityService.getCurrentIdentity()).thenReturn(self);

    UserEntity stored = new UserEntity();
    stored.setId("u1");
    stored.getSettings().setHasConsented(true);
    stored.getSettings().setIsFirstVisit(false);
    when(userRepository.findByIdAndStatus("u1", UserStatus.active)).thenReturn(Optional.of(stored));
    when(userRepository.save(any(UserEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UserRequest request = new UserRequest();
    request.setSettings(java.util.Map.of("isShowGetStarted", false));
    userService.updateCurrentProfile(request);

    ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(saved.capture());
    UserSettings settings = saved.getValue().getSettings();
    assertThat(settings.getIsShowGetStarted()).isFalse();
    assertThat(settings.getHasConsented()).isTrue();
    assertThat(settings.getIsFirstVisit()).isFalse();
    assertThat(settings.getIsShowHelp()).isTrue();
  }

  /**
   * The behaviour the exact-match switch must not regress: a user types their address in whatever
   * case they like, and the lookup still finds the lower-cased row. UserService normalises the
   * incoming value, so the repository only ever sees lower case.
   */
  @Test
  void mixedCaseLoginEmailResolvesTheLowerCasedStoredUser() {
    UserEntity stored = new UserEntity();
    stored.setId("u1");
    stored.setEmail("ada.lovelace@example.com");
    when(userRepository.findByEmailAndStatus("ada.lovelace@example.com", UserStatus.active))
        .thenReturn(stored);

    assertThat(userService.getUserByEmail("Ada.Lovelace@EXAMPLE.com"))
        .get()
        .extracting(UserEntity::getId)
        .isEqualTo("u1");

    verify(userRepository).findByEmailAndStatus("ada.lovelace@example.com", UserStatus.active);
  }

  /**
   * The write path: a first-time login with a mixed-case address must persist lower case, or the
   * exact-match lookup above could never find it again.
   */
  @Test
  void registeringANewUserStoresTheEmailLowerCased() {
    when(userRepository.findByEmailAndStatus("ada.lovelace@example.com", UserStatus.active))
        .thenReturn(null);
    when(userRepository.save(any(UserEntity.class)))
        .thenAnswer(
            invocation -> {
              UserEntity saved = invocation.getArgument(0);
              saved.setId("u1");
              return saved;
            });

    Optional<UserEntity> registered =
        userService.getAndRegisterUser(
            "Ada.Lovelace@EXAMPLE.com", Optional.empty(), Optional.empty(), Optional.empty(), true);

    assertThat(registered).get().extracting(UserEntity::getEmail).isEqualTo("ada.lovelace@example.com");

    ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("ada.lovelace@example.com");
    // The user relationship node's slug is a copy of the email and carries the same normalisation.
    verify(relationshipService)
        .createNodeAndEdge(
            any(), any(), any(), any(), eq("u1"), eq("ada.lovelace@example.com"), any(), any());
  }

  /** An already-lower-case address is stored unchanged - normalisation only ever touches case. */
  @Test
  void registeringAnAlreadyLowerCaseEmailLeavesItUnchanged() {
    when(userRepository.findByEmailAndStatus("grace.hopper@example.com", UserStatus.active))
        .thenReturn(null);
    when(userRepository.save(any(UserEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Optional<UserEntity> registered =
        userService.getAndRegisterUser(
            "grace.hopper@example.com",
            Optional.of("Grace"),
            Optional.empty(),
            Optional.empty(),
            true);

    assertThat(registered).get().extracting(UserEntity::getEmail).isEqualTo("grace.hopper@example.com");
  }

  /**
   * The admin update-by-email path ({@code apply} with no id) normalises too - an admin pasting a
   * mixed-case address must still land on the stored user.
   */
  @Test
  void applyByMixedCaseEmailQueriesTheLowerCasedValue() {
    // The update-by-email shape carries no target id, so it is never self: apply requires a
    // global-scoped user/write grant - the admin this test's javadoc already assumes.
    io.boomerang.core.model.Token admin =
        new io.boomerang.core.model.Token(io.boomerang.core.security.enums.AuthScope.session);
    admin.setPrincipal("admin-id");
    admin.setPermissions(
        java.util.List.of(
            new io.boomerang.core.security.model.ResolvedPermissions(
                io.boomerang.core.security.enums.PermissionScope.global,
                "**",
                java.util.List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(admin);

    UserEntity stored = new UserEntity();
    stored.setId("u1");
    stored.setEmail("ada.lovelace@example.com");
    when(userRepository.findByEmailAndStatus("ada.lovelace@example.com", UserStatus.active))
        .thenReturn(stored);
    when(userRepository.save(any(UserEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UserRequest request = new UserRequest();
    request.setEmail("ADA.LOVELACE@example.COM");
    request.setDisplayName("Ada");

    assertThat(userService.apply(request).getId()).isEqualTo("u1");

    verify(userRepository).findByEmailAndStatus("ada.lovelace@example.com", UserStatus.active);
  }

  /**
   * Privilege is never asserted for a principal that is not a user: the security-off system
   * identity is global-scoped, but it maps to no user record, so the admin gate stays closed -
   * the same answer the deleted no-principal branch gave.
   */
  @Test
  void machinePrincipalWithNoUserRecordIsCurrentUserAdminReturnsFalse() {
    when(identityService.getCurrentPrincipal()).thenReturn("system");
    when(userRepository.findById("system")).thenReturn(java.util.Optional.empty());

    assertThat(userService.isCurrentUserAdmin()).isFalse();
  }

  @Test
  void resolvedAdminPrincipalIsCurrentUserAdminReturnsTrue() {
    UserEntity entity = new UserEntity();
    entity.setId("u1");
    entity.setType(UserType.admin);
    when(identityService.getCurrentPrincipal()).thenReturn("u1");
    when(userRepository.findById("u1")).thenReturn(java.util.Optional.of(entity));

    assertThat(userService.isCurrentUserAdmin()).isTrue();
  }

  // A person an admin added to a workspace by email is stored inactive; signing in activates them.
  @Test
  void aSignInActivatesAUserAnAdminAddedByEmail() {
    UserEntity invited = new UserEntity();
    invited.setId("u-invited");
    invited.setEmail("grace.hopper@example.com");
    invited.setStatus(UserStatus.inactive);
    when(userRepository.findByEmailAndStatus("grace.hopper@example.com", UserStatus.active))
        .thenReturn(null);
    when(userRepository.findByEmail("grace.hopper@example.com")).thenReturn(invited);
    when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));

    Optional<UserEntity> signedIn =
        userService.getAndRegisterUser(
            "Grace.Hopper@example.com", Optional.empty(), Optional.empty(), Optional.empty(), false);

    assertThat(signedIn).get().extracting(UserEntity::getId).isEqualTo("u-invited");
    assertThat(signedIn.get().getStatus()).isEqualTo(UserStatus.active);
    verify(relationshipService, never()).createNodeAndEdge(any(), any(), any(), any(), any(), any(), any(), any());
  }

  // Adding the same person to a second workspace finds the invitation; it neither activates nor duplicates it.
  @Test
  void addingAnAlreadyInvitedPersonAgainReturnsTheInvitationUnchanged() {
    UserEntity invited = new UserEntity();
    invited.setId("u-invited");
    invited.setEmail("grace.hopper@example.com");
    invited.setStatus(UserStatus.inactive);
    when(userRepository.findByEmailAndStatus("grace.hopper@example.com", UserStatus.active))
        .thenReturn(null);
    when(userRepository.findByEmail("grace.hopper@example.com")).thenReturn(invited);

    Optional<UserEntity> added =
        userService.getAndRegisterUser(
            "grace.hopper@example.com",
            Optional.empty(),
            Optional.of(UserType.user),
            Optional.of(UserStatus.inactive),
            true);

    assertThat(added).containsSame(invited);
    assertThat(invited.getStatus()).isEqualTo(UserStatus.inactive);
    verify(userRepository, never()).save(any(UserEntity.class));
  }

  @Test
  void aDeletedUsersRecordIsNotRevivedBySigningIn() {
    UserEntity gone = new UserEntity();
    gone.setId("u-gone");
    gone.setEmail("grace.hopper@example.com");
    gone.setStatus(UserStatus.deleted);
    when(userRepository.findByEmailAndStatus("grace.hopper@example.com", UserStatus.active))
        .thenReturn(null);
    when(userRepository.findByEmail("grace.hopper@example.com")).thenReturn(gone);

    assertThat(
            userService.getAndRegisterUser(
                "grace.hopper@example.com", Optional.empty(), Optional.empty(), Optional.empty(), false))
        .isEmpty();
  }

  @Test
  void theAdminClaimPromotesAUserAndDemotesAnAdminButLeavesOtherTypesAlone() {
    when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    UserEntity user = userOfType(UserType.user);
    UserEntity admin = userOfType(UserType.admin);
    UserEntity operator = userOfType(UserType.operator);

    assertThat(userService.applyAdminClaim(user, true)).isTrue();
    assertThat(user.getType()).isEqualTo(UserType.admin);
    assertThat(userService.applyAdminClaim(admin, false)).isTrue();
    assertThat(admin.getType()).isEqualTo(UserType.user);
    assertThat(userService.applyAdminClaim(operator, false)).isFalse();
    assertThat(operator.getType()).isEqualTo(UserType.operator);
    assertThat(userService.applyAdminClaim(userOfType(UserType.admin), true)).isFalse();
  }

  @Test
  void theAdminClaimNeverWritesTheExternalDirectorysUsers() {
    ReflectionTestUtils.setField(userService, "externalUserUrl", "http://users.example.test");

    assertThat(userService.applyAdminClaim(userOfType(UserType.user), true)).isFalse();
    verify(userRepository, never()).save(any(UserEntity.class));
  }

  @Test
  void onlyTheConfiguredActivationCodeActivates() {
    assertThat(userService.activationCodeValid(CODE)).isTrue();
    assertThat(userService.activationCodeValid("wrong-code")).isFalse();
    assertThat(userService.activationCodeValid("")).isFalse();
    assertThat(userService.activationCodeValid(null)).isFalse();
    assertThat(userService.activateSetup(null, false).getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void anUnsetCodeActivatesNobody() {
    UserService noCode =
        new UserService(
            mongoTemplate, identityService, extUserService, relationshipService, userRepository,
            JsonMapper.builder().build(), new ActivationCode("", ""));
    ReflectionTestUtils.setField(noCode, "externalUserUrl", "");

    assertThat(noCode.activationCodeValid("")).isFalse();
    assertThat(noCode.activationCodeValid(CODE)).isFalse();
  }

  @Test
  void anExternalUserDirectoryIsNeverActivatedByCode() {
    ReflectionTestUtils.setField(userService, "externalUserUrl", "http://users.example.test");

    assertThat(userService.activationCodeValid(CODE)).isFalse();
  }

  // The code is useful once: after the install has users, the endpoint refuses even the right code.
  @Test
  void theActivateEndpointRefusesOnceTheInstallHasUsers() {
    OneTimeCode right = new OneTimeCode();
    right.setOtc(CODE);

    when(userRepository.count()).thenReturn(0L);
    assertThat(userService.activateSetup(right, false).getStatusCode().value()).isEqualTo(200);

    when(userRepository.count()).thenReturn(1L);
    assertThat(userService.activateSetup(right, false).getStatusCode().value()).isEqualTo(400);
    // Except for the request that just created the first admin.
    assertThat(userService.activateSetup(right, true).getStatusCode().value()).isEqualTo(200);
  }

  private static UserEntity userOfType(UserType type) {
    UserEntity entity = new UserEntity();
    entity.setId("id-" + type);
    entity.setType(type);
    return entity;
  }
}
