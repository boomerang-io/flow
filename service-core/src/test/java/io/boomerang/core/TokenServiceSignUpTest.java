package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.AdminClaim;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.TokenLookupCache;
import io.boomerang.core.security.enums.AuthScope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Who a sign-in admits: the auto-create users setting, the first sign-in on an empty install, and the identity
 * provider's admin claim, in each combination. The claim verdict is {@code TRUE}/{@code FALSE}
 * when the path read a claim and {@code null} when it carries none.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceSignUpTest {

  private static final String EMAIL = "person@example.test";

  @Mock private TokenRepository tokenRepository;
  @Mock private UserService userService;
  @Mock private RoleRepository roleRepository;
  @Mock private RelationshipService relationshipService;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private IdentityService identityService;
  @Mock private SettingsService settingsService;

  private final AdminClaim noClaim = new AdminClaim("", List.of(), false);
  private final AdminClaim claim = new AdminClaim("groups", List.of("flow-admins"), false);

  @BeforeEach
  void setUp() {
    lenient().when(tokenRepository.save(any(TokenEntity.class))).thenAnswer(i -> i.getArgument(0));
    lenient().when(relationshipService.roles(any())).thenReturn(Map.of());
    RoleEntity adminRole = new RoleEntity();
    adminRole.setPermissions(List.of("**/**"));
    lenient().when(roleRepository.findByTypeAndName("global", "admin")).thenReturn(adminRole);
  }

  private TokenService service(AdminClaim adminClaim) {
    TokenService service =
        new TokenService(
            tokenRepository,
            userService,
            roleRepository,
            relationshipService,
            mongoTemplate,
            identityService,
            new TokenLookupCache(true, Duration.ofSeconds(60), 10_000),
            settingsService,
            adminClaim);
    ReflectionTestUtils.setField(service, "MAX_SESSION_TOKEN_DURATION", 8);
    return service;
  }

  private static UserEntity user(String id, UserType type) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setType(type);
    return user;
  }

  private void install(boolean activated, boolean inviteOnly) {
    when(userService.isActivated()).thenReturn(activated);
    lenient().when(settingsService.autoCreateUsers()).thenReturn(!inviteOnly);
  }

  private void registers(UserType type, boolean creationAllowed, UserEntity result) {
    when(userService.getAndRegisterUser(
            eq(EMAIL), any(), eq(Optional.of(type)), any(), eq(creationAllowed)))
        .thenReturn(Optional.ofNullable(result));
  }

  // --- auto-create users on, no claim: today's behaviour -------------------------------------------

  @Test
  void openFirstSignInOnAnEmptyInstallBecomesAdminOwnerOfTheSystemWorkspace() {
    install(false, false);
    registers(UserType.admin, true, user("u1", UserType.admin));

    service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true);

    verify(relationshipService)
        .createEdge(
            eq(RelationshipType.USER), eq("u1"), eq(RelationshipLabel.MEMBER_OF),
            eq(RelationshipType.WORKSPACE), eq("system"), any());
  }

  @Test
  void openSignInOfAnUnknownIdentityRegistersAUser() {
    install(true, false);
    registers(UserType.user, true, user("u2", UserType.user));

    assertThat(service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true).token().getPrincipal())
        .isEqualTo("u2");
    verify(userService, never()).applyAdminClaim(any(), anyBoolean());
  }

  // --- auto-create users off ----------------------------------------------------------------------------

  @Test
  void inviteOnlyRefusesAnIdentityThatIsNoUserYet() {
    install(true, true);
    registers(UserType.user, false, null);

    assertThatThrownBy(() -> service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void inviteOnlyAdmitsAUserAnAdminAlreadyAdded() {
    install(true, true);
    registers(UserType.user, false, user("u3", UserType.user));

    assertThat(service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true).token().getPrincipal())
        .isEqualTo("u3");
  }

  @Test
  void inviteOnlyCreatesAnAdminWhenTheClaimMakesOne() {
    install(true, true);
    registers(UserType.admin, true, user("u4", UserType.admin));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.TRUE);
  }

  @Test
  void inviteOnlyRefusesAnUnknownIdentityWhoseClaimDoesNotMakeThemAdmin() {
    install(true, true);
    registers(UserType.user, false, null);

    assertThatThrownBy(
            () -> service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.FALSE))
        .isInstanceOf(HttpClientErrorException.class);
  }

  // Without a claim the one-time code alone makes the first admin, with auto-create users on or off.
  @Test
  void anEmptyInstallIsLockedUntilTheOneTimeCodeIsProven() {
    install(false, false);

    assertThatThrownBy(() -> service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.LOCKED));
    verify(userService, never()).getAndRegisterUser(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void inviteOnlyFirstSignInWithTheProvenCodeCreatesTheAdmin() {
    install(false, true);
    registers(UserType.admin, true, user("u12", UserType.admin));

    service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true);

    verify(relationshipService).createEdge(any(), eq("u12"), any(), any(), eq("system"), any());
  }

  @Test
  void inviteOnlyFirstSignInWithoutTheCodeCreatesNobody() {
    install(false, true);

    assertThatThrownBy(() -> service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true))
        .isInstanceOf(HttpClientErrorException.class);
    verify(userService, never()).getAndRegisterUser(any(), any(), any(), any(), anyBoolean());
  }

  // With a claim configured the code plays no part: the claim makes the admin without it.
  @Test
  void withAClaimConfiguredTheFirstAdminNeedsNoCode() {
    install(false, false);
    registers(UserType.admin, true, user("u13", UserType.admin));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.TRUE);

    verify(relationshipService).createEdge(any(), eq("u13"), any(), any(), eq("system"), any());
  }

  @Test
  void withAClaimConfiguredAProvenCodeStillMakesNobodyAdminWithoutTheClaim() {
    install(false, false);
    registers(UserType.user, true, user("u14", UserType.user));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true, Boolean.FALSE);

    verify(relationshipService, never()).createEdge(any(), any(), any(), any(), any(), any());
  }

  @Test
  void inviteOnlyFirstSignInWithTheClaimCreatesTheAdmin() {
    install(false, true);
    registers(UserType.admin, true, user("u5", UserType.admin));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true, Boolean.TRUE);

    verify(relationshipService).createEdge(any(), eq("u5"), any(), any(), eq("system"), any());
  }

  // --- first sign-in with a claim configured --------------------------------------------------

  @Test
  void withAClaimConfiguredTheFirstSignInIsNotAdminByBeingFirst() {
    install(false, false);
    registers(UserType.user, true, user("u6", UserType.user));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true, Boolean.FALSE);

    verify(relationshipService, never()).createEdge(any(), any(), any(), any(), any(), any());
  }

  @Test
  void withAClaimConfiguredAPathThatReadsNoClaimNeverMakesTheFirstSignInAdmin() {
    install(false, false);
    registers(UserType.user, true, user("u6", UserType.user));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true, null);

    verify(relationshipService, never()).createEdge(any(), any(), any(), any(), any(), any());
  }

  @Test
  void withAClaimConfiguredTheFirstSignInIsAdminWhenTheClaimSaysSo() {
    install(false, false);
    registers(UserType.admin, true, user("u7", UserType.admin));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", true, true, Boolean.TRUE);

    verify(relationshipService).createEdge(any(), eq("u7"), any(), any(), eq("system"), any());
  }

  // --- admin follows the claim on every sign-in -------------------------------------------------

  @Test
  void aClaimThatNoLongerMatchesDemotesTheAdminAndEndsTheirSessions() {
    install(true, false);
    UserEntity admin = user("u8", UserType.admin);
    registers(UserType.user, true, admin);
    when(userService.applyAdminClaim(admin, false)).thenReturn(true);
    TokenEntity session = new TokenEntity();
    session.setId("session-1");
    session.setToken("hash-1");
    when(tokenRepository.findByPrincipalAndType("u8", AuthScope.session))
        .thenReturn(Optional.of(List.of(session)));
    when(tokenRepository.findById("session-1")).thenReturn(Optional.of(session));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.FALSE);

    verify(tokenRepository).delete(session);
  }

  @Test
  void aMatchingClaimPromotesAnExistingUser() {
    install(true, false);
    UserEntity existing = user("u9", UserType.user);
    when(userService.getAndRegisterUser(eq(EMAIL), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(existing));
    when(userService.applyAdminClaim(existing, true)).thenReturn(true);

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.TRUE);

    verify(userService).applyAdminClaim(existing, true);
    verify(tokenRepository, never()).findByPrincipalAndType(any(), any());
  }

  @Test
  void aPathThatReadsNoClaimLeavesTheUsersTypeAlone() {
    install(true, false);
    UserEntity admin = user("u10", UserType.admin);
    when(userService.getAndRegisterUser(eq(EMAIL), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(admin));

    service(claim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, null);

    verify(userService, never()).applyAdminClaim(any(), anyBoolean());
  }

  @Test
  void withoutAConfiguredClaimAVerdictChangesNothing() {
    install(true, false);
    UserEntity admin = user("u11", UserType.admin);
    when(userService.getAndRegisterUser(eq(EMAIL), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(admin));

    service(noClaim).createSessionTokenWithRaw(EMAIL, "A", "B", false, true, Boolean.FALSE);

    verify(userService, never()).applyAdminClaim(any(), anyBoolean());
  }
}
