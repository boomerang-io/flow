package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * T6-3 (actor/ceiling-typed token model) coverage. Deliberately unit-level (mocked repositories,
 * no Spring context / MockMvc) so it exercises {@code TokenService}'s enforcement DECISIONS
 * directly — this matters here specifically because {@code SecurityInterceptor} soft-fails
 * permission mismatches (logs + allows), so an end-to-end request test could pass even if
 * authorization semantics regressed; these tests instead assert on the actual guard methods and
 * thrown exceptions.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

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
            tokenRepository,
            userService,
            roleRepository,
            relationshipService,
            mongoTemplate,
            identityService);
  }

  // =====================================================================================
  // Invariant #2 — `global` token creation requires the caller to already hold a
  // `global`-scoped grant (admin authority), enforced in TokenService#create, not just
  // documented.
  // =====================================================================================

  @Test
  void globalTokenCreationByNonAdminIsRejected() {
    when(identityService.getCurrentIdentity()).thenReturn(workspaceScopedIdentity());

    assertThatThrownBy(() -> tokenService.create(globalRequest()))
        .asInstanceOf(InstanceOfAssertFactories.type(BoomerangException.class))
        .extracting(BoomerangException::getReason)
        .isEqualTo(BoomerangError.TOKEN_ADMIN_REQUIRED.getReason());
  }

  @Test
  void globalTokenCreationWithNoAuthenticatedIdentityIsRejected() {
    when(identityService.getCurrentIdentity()).thenReturn(null);

    assertThatThrownBy(() -> tokenService.create(globalRequest()))
        .asInstanceOf(InstanceOfAssertFactories.type(BoomerangException.class))
        .extracting(BoomerangException::getReason)
        .isEqualTo(BoomerangError.TOKEN_ADMIN_REQUIRED.getReason());
  }

  @Test
  void globalTokenCreationByAdminSucceedsAndCreatedByIsServerInjected() {
    when(tokenRepository.save(any(TokenEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Token admin = globalScopedIdentity("real-admin-principal");
    when(identityService.getCurrentIdentity()).thenReturn(admin);

    TokenCreateResponse response = tokenService.create(globalRequest());

    assertThat(response).isNotNull();
    assertThat(response.getType()).isEqualTo(AuthScope.global);
    // TokenCreateRequest has no createdBy field at all - the DTO offers no way to spoof it. The
    // saved entity's createdBy can only ever be the server-resolved identity's own principal
    // (invariant #3).
    ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
    verify(tokenRepository).save(captor.capture());
    assertThat(captor.getValue().getCreatedBy()).isEqualTo("real-admin-principal");
  }

  // =====================================================================================
  // Invariant #1 — a `key` token may never carry a `global`-scoped grant, enforced at mint
  // time. The public create() API cannot organically build this shape (grant scope is always
  // derived server-side from the token's own class - see the key-token tests below), so this is
  // proven directly against the enforcement method itself, per the task's "add a test proving
  // it" against a constructed entity.
  // =====================================================================================

  @Test
  void keyTokenWithGlobalGrantIsRejectedAtMintTime() {
    TokenEntity entity = new TokenEntity();
    entity.setType(AuthScope.key);
    entity.setPrincipal("workspace-a");
    entity
        .getPermissions()
        .add(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**")));

    assertThatThrownBy(() -> tokenService.assertKeyTokenCeiling(entity))
        .asInstanceOf(InstanceOfAssertFactories.type(BoomerangException.class))
        .extracting(BoomerangException::getReason)
        .isEqualTo(BoomerangError.TOKEN_INVALID_CEILING.getReason());
  }

  @Test
  void keyTokenWithOnlyWorkspaceGrantsPassesTheCeilingGuard() {
    TokenEntity entity = new TokenEntity();
    entity.setType(AuthScope.key);
    entity
        .getPermissions()
        .add(new ResolvedPermissions(PermissionScope.workspace, "workspace-a", List.of("**/**")));

    assertThatCode(() -> tokenService.assertKeyTokenCeiling(entity)).doesNotThrowAnyException();
  }

  @Test
  void publicCreateApiNeverProducesAGlobalGrantForAKeyToken() {
    when(tokenRepository.save(any(TokenEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("ci-key-token");
    request.setPrincipal("workspace-a");
    request.setPermissions(List.of("workflow/read"));

    tokenService.create(request);

    ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
    verify(tokenRepository).save(captor.capture());
    assertThat(captor.getValue().getPermissions())
        .extracting(ResolvedPermissions::getScope)
        .containsOnly(PermissionScope.workspace);
  }

  // =====================================================================================
  // A `key` token holding grants for TWO workspaces (incident.io's plural `team_ids` case) —
  // both the data shape and the authorization-decision (permission-action matching, mirroring
  // SecurityInterceptor's own regex check) resolve independently for each workspace grant.
  // =====================================================================================

  @Test
  void keyTokenCanHoldGrantsForMultipleWorkspaces() {
    TokenEntity entity = new TokenEntity();
    entity.setType(AuthScope.key);
    entity.setActorKind(TokenActorKind.SERVICE);
    entity
        .getPermissions()
        .add(
            new ResolvedPermissions(
                PermissionScope.workspace, "workspace-a", List.of("workflow/read")));
    entity
        .getPermissions()
        .add(
            new ResolvedPermissions(
                PermissionScope.workspace, "workspace-b", List.of("workflow/write")));

    assertThatCode(() -> tokenService.assertKeyTokenCeiling(entity)).doesNotThrowAnyException();
    assertThat(entity.getPermissions())
        .extracting(ResolvedPermissions::getPrincipal)
        .containsExactlyInAnyOrder("workspace-a", "workspace-b");
    assertThat(entity.getPermissions())
        .extracting(ResolvedPermissions::getScope)
        .containsOnly(PermissionScope.workspace);

    // Same permission-action matching SecurityInterceptor performs (a permission mismatch there
    // only counts a metric and allows the request - see the testing hazard - so this asserts on
    // the DECISION itself rather than an HTTP status).
    assertThat(matchesRequiredAction(entity, "workflow", "read")).isTrue();
    assertThat(matchesRequiredAction(entity, "workflow", "write")).isTrue();
    assertThat(matchesRequiredAction(entity, "task", "read")).isFalse();
  }

  private boolean matchesRequiredAction(TokenEntity entity, String resource, String action) {
    String requiredRegex = "(\\*{2}|" + resource + ")\\/(\\*{2}|" + action + ")";
    return entity.getPermissions().stream()
        .anyMatch(p -> p.getActions().stream().anyMatch(a -> a.matches(requiredRegex)));
  }

  // =====================================================================================
  // Helpers
  // =====================================================================================

  private TokenCreateRequest globalRequest() {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("platform-admin-token");
    request.setPermissions(List.of("**/**"));
    return request;
  }

  private Token workspaceScopedIdentity() {
    Token identity = new Token(AuthScope.key);
    identity.setPrincipal("workspace-a");
    identity.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, "workspace-a", List.of("**/**"))));
    return identity;
  }

  private Token globalScopedIdentity(String principal) {
    Token identity = new Token(AuthScope.global);
    identity.setPrincipal(principal);
    identity.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    return identity;
  }
}
