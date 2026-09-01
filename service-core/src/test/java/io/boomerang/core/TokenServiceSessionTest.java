package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.boomerang.core.TokenService.SessionToken;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import java.time.Duration;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The session-minting core behind {@code POST /api/v2/auth/exchange} (specifications/authentication.md
 * §1): the raw {@code bfs_<uuid>} value must be recoverable by a caller that needs to hand it to a
 * browser (the exchange endpoint's cookie), while the entity only ever persists its hash - and
 * {@code createSessionTokenForUser} must skip get-or-register entirely for an already-resolved user
 * (the proxy-exchange path, where AuthenticationFilter resolved/registered the user moments earlier).
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceSessionTest {

  @Mock private TokenRepository tokenRepository;
  @Mock private UserService userService;
  @Mock private RoleRepository roleRepository;
  @Mock private RelationshipService relationshipService;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private IdentityService identityService;

  private TokenService tokenService;

  @BeforeEach
  void setUp() {
    tokenService =
        new TokenService(
            tokenRepository, userService, roleRepository, relationshipService, mongoTemplate, identityService);
    ReflectionTestUtils.setField(tokenService, "MAX_SESSION_TOKEN_DURATION", 8);
    when(tokenRepository.save(any(TokenEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(relationshipService.roles(any())).thenReturn(Map.of());
  }

  @Test
  void createSessionTokenForUserMintsARawValueDistinctFromTheStoredHash() {
    UserEntity user = new UserEntity();
    user.setId("user-1");
    user.setType(UserType.user);

    SessionToken session = tokenService.createSessionTokenForUser(user);

    assertThat(session.rawToken()).startsWith("bfs_");
    assertThat(session.token().getType()).isEqualTo(AuthScope.session);
    assertThat(session.token().getPrincipal()).isEqualTo("user-1");

    // The persisted entity only ever carries the SHA-256 hash - the raw value never round-trips
    // back out through the Token model (Token has no token/hash field at all).
    ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
    verify(tokenRepository).save(captor.capture());
    assertThat(captor.getValue().getToken()).isEqualTo(tokenService.hashString(session.rawToken()));
    assertThat(captor.getValue().getToken()).isNotEqualTo(session.rawToken());
  }

  @Test
  void createSessionTokenForUserNeverCallsGetOrRegister() {
    UserEntity user = new UserEntity();
    user.setId("user-1");
    user.setType(UserType.user);

    tokenService.createSessionTokenForUser(user);

    verifyNoInteractions(userService);
  }

  @Test
  void createSessionTokenWithRawResolvesTheUserAndReturnsTheRawValue() {
    UserEntity registered = new UserEntity();
    registered.setId("user-2");
    registered.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("person@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(registered));

    SessionToken session =
        tokenService.createSessionTokenWithRaw("person@example.test", "Person", "Example", false, true);

    assertThat(session.rawToken()).startsWith("bfs_");
    assertThat(session.token().getPrincipal()).isEqualTo("user-2");
  }

  /*
   * AuthenticationFilter's header-resolved branches (forwarded email, raw JWT, Basic) call
   * createSessionToken on EVERY request - without reuse, each request persists a brand-new
   * TokenEntity (specifications/authentication.md flags this write amplification).
   */
  @Test
  void repeatedFilterAuthenticationReusesTheMintedSessionToken() {
    UserEntity registered = new UserEntity();
    registered.setId("user-2");
    registered.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("person@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(registered));

    Token first =
        tokenService.createSessionToken("person@example.test", "Person", "Example", false, true);
    Token second =
        tokenService.createSessionToken("person@example.test", "Person", "Example", false, true);
    Token third =
        tokenService.createSessionToken("person@example.test", "Person", "Example", false, true);

    assertThat(second).isSameAs(first);
    assertThat(third).isSameAs(first);
    verify(tokenRepository, times(1)).save(any(TokenEntity.class));
  }

  @Test
  void distinctIdentitiesNeverShareAMintedSessionToken() {
    UserEntity personA = new UserEntity();
    personA.setId("user-a");
    personA.setType(UserType.user);
    UserEntity personB = new UserEntity();
    personB.setId("user-b");
    personB.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("a@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(personA));
    when(userService.getAndRegisterUser(eq("b@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(personB));

    Token tokenA = tokenService.createSessionToken("a@example.test", null, null, false, true);
    Token tokenB = tokenService.createSessionToken("b@example.test", null, null, false, true);

    assertThat(tokenA.getPrincipal()).isEqualTo("user-a");
    assertThat(tokenB.getPrincipal()).isEqualTo("user-b");
    verify(tokenRepository, times(2)).save(any(TokenEntity.class));
  }

  /*
   * The reuse map must not grow without bound: cache keys derive from caller-supplied identity
   * (x-forwarded-email), so entries whose window has passed are swept on each put - the map only
   * ever holds identities seen within the last window.
   */
  @Test
  void aPutEvictsEntriesWhoseWindowHasPassed() {
    UserEntity personA = new UserEntity();
    personA.setId("user-a");
    personA.setType(UserType.user);
    UserEntity personB = new UserEntity();
    personB.setId("user-b");
    personB.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("a@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(personA));
    when(userService.getAndRegisterUser(eq("b@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(personB));
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ZERO);

    tokenService.createSessionToken("a@example.test", null, null, false, true);
    tokenService.createSessionToken("b@example.test", null, null, false, true);

    @SuppressWarnings("unchecked")
    Map<String, ?> reuseMap =
        (Map<String, ?>) ReflectionTestUtils.getField(tokenService, "mintedSessionsByEmail");
    assertThat(reuseMap).containsOnlyKeys("b@example.test");
  }

  @Test
  void aStaleReuseEntryIsRemintedNotServed() {
    UserEntity registered = new UserEntity();
    registered.setId("user-2");
    registered.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("person@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(registered));
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ZERO);

    tokenService.createSessionToken("person@example.test", "Person", "Example", false, true);
    tokenService.createSessionToken("person@example.test", "Person", "Example", false, true);

    verify(tokenRepository, times(2)).save(any(TokenEntity.class));
  }

  /*
   * The exchange endpoint's mint paths must NEVER be served from the filter's reuse cache: every
   * exchange hands a fresh raw bfs_ value to a browser cookie, so each call must persist its own
   * hash.
   */
  @Test
  void everyExchangeMintPersistsItsOwnSessionToken() {
    UserEntity registered = new UserEntity();
    registered.setId("user-2");
    registered.setType(UserType.user);
    when(userService.isActivated()).thenReturn(true);
    when(userService.getAndRegisterUser(eq("person@example.test"), any(), any(), any(), eq(true)))
        .thenReturn(Optional.of(registered));

    SessionToken first =
        tokenService.createSessionTokenWithRaw(
            "person@example.test", "Person", "Example", false, true);
    SessionToken second =
        tokenService.createSessionTokenWithRaw(
            "person@example.test", "Person", "Example", false, true);

    assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
    verify(tokenRepository, times(2)).save(any(TokenEntity.class));
  }
}
