package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.UserRequest;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.IdentityService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

  @Test
  void noPrincipalGetCurrentUserReturnsNullNotNpe() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);

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

    verify(userRepository, never()).findByEmailIgnoreCaseAndStatus(any(), any());
  }

  @Test
  void noPrincipalIsCurrentUserAdminReturnsFalseNotNpe() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);

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
