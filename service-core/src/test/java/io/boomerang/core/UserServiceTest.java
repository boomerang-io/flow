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
import io.boomerang.core.model.UserRequest;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.IdentityService;
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

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserService(mongoTemplate, identityService, extUserService, relationshipService, userRepository);
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
}
