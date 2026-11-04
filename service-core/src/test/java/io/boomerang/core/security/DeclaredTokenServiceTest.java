package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Declared tokens are registered on start from a hash or a raw value, survive a restart unchanged,
 * are replaced when their entry changes, are revoked when it is removed, can be valid side by side,
 * hold only the permissions declared, and cannot be deleted through the token API.
 */
class DeclaredTokenServiceTest extends AbstractEngineIntegrationTest {

  private static final String FIRST = "bfg_" + "a".repeat(40);
  private static final String SECOND = "bfg_" + "b".repeat(40);
  private static final String WORKSPACE_QUERY = "/api/v2/workspace/query";

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private TokenLookupCache lookupCache;
  @Autowired private WebApplicationContext context;
  @Autowired private DeclaredTokenProperties configured;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  @AfterEach
  void removeDeclaredTokens() {
    tokenRepository.deleteAll(tokenRepository.findByIdStartingWith(DeclaredTokenService.ID_PREFIX));
    lookupCache.evictAll();
    SecurityContextHolder.clearContext();
  }

  private Map.Entry<String, DeclaredTokenProperties.Entry> byValue(
      String name, String value, String... permissions) {
    return Map.entry(
        name,
        new DeclaredTokenProperties.Entry(
            null, value, TokenActorKind.SERVICE, List.of(permissions), null, null));
  }

  private Map.Entry<String, DeclaredTokenProperties.Entry> byHash(
      String name, String raw, String... permissions) {
    return Map.entry(
        name,
        new DeclaredTokenProperties.Entry(
            tokenService.hashString(raw),
            null,
            TokenActorKind.SERVICE,
            List.of(permissions),
            null,
            null));
  }

  @SafeVarargs
  private void register(Map.Entry<String, DeclaredTokenProperties.Entry>... entries) {
    Map<String, DeclaredTokenProperties.Entry> tokens = new LinkedHashMap<>();
    for (Map.Entry<String, DeclaredTokenProperties.Entry> entry : entries) {
      tokens.put(entry.getKey(), entry.getValue());
    }
    new DeclaredTokenService(
            new DeclaredTokenProperties(tokens), tokenService, tokenRepository, lookupCache)
        .register();
  }

  private TokenEntity stored(String name) {
    return tokenRepository.findById(DeclaredTokenService.ID_PREFIX + name).orElseThrow();
  }

  @Test
  void nothingIsDeclaredByDefault() {
    assertThat(configured.tokens()).isEmpty();
  }

  @Test
  void aHashEntryIsRegisteredAsAGlobalServiceTokenWithTheDeclaredGrants() {
    register(byHash("dispatcher", FIRST, "workspace/read", "workflowrun/write"));

    assertThat(tokenService.validate(FIRST)).isTrue();
    TokenEntity entity = stored("dispatcher");
    assertThat(entity.getType()).isEqualTo(AuthScope.global);
    assertThat(entity.getActorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(entity.getToken()).isEqualTo(tokenService.hashString(FIRST));
    assertThat(entity.getPermissions()).hasSize(1);
    assertThat(entity.getPermissions().get(0).getPrincipal()).isEqualTo("**");
    assertThat(entity.getPermissions().get(0).getActions())
        .containsExactly("workspace/read", "workflowrun/write");
  }

  @Test
  void aValueEntryIsStoredAsItsHashNeverAsTheValue() {
    register(byValue("backend", FIRST, "workspace/read"));

    assertThat(tokenService.validate(FIRST)).isTrue();
    assertThat(stored("backend").getToken()).isEqualTo(tokenService.hashString(FIRST));
  }

  @Test
  void theDocumentedShellCommandProducesTheStoredHash() throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder("sh", "-c", "printf %s \"$RAW\" | (sha256sum 2>/dev/null || shasum -a 256)")
            .redirectErrorStream(true);
    builder.environment().put("RAW", FIRST);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    Assumptions.assumeTrue(process.waitFor() == 0 && output.length() >= 64);

    assertThat(output.substring(0, 64)).isEqualTo(tokenService.hashString(FIRST));
  }

  @Test
  void expiryAndDescriptionAreStoredAndAnExpiredTokenStopsWorking() {
    Instant expiry = Instant.now().plusSeconds(3600);
    register(
        Map.entry(
            "timed",
            new DeclaredTokenProperties.Entry(
                tokenService.hashString(FIRST), null, TokenActorKind.SERVICE,
                List.of("workspace/read"), "for the backend", expiry.toString())));

    TokenEntity entity = stored("timed");
    assertThat(entity.getDescription()).isEqualTo("for the backend");
    assertThat(entity.getExpirationDate().toInstant().toEpochMilli()).isEqualTo(expiry.toEpochMilli());
    assertThat(tokenService.validate(FIRST)).isTrue();

    entity.setExpirationDate(java.util.Date.from(Instant.now().minusSeconds(60)));
    tokenRepository.save(entity);
    lookupCache.evictAll();
    assertThat(tokenService.validate(FIRST)).isFalse();
  }

  @Test
  void registeringTheSameEntryAgainChangesNothing() {
    register(byHash("dispatcher", FIRST, "workspace/read"));
    TokenEntity first = stored("dispatcher");

    register(byHash("dispatcher", FIRST, "workspace/read"));

    assertThat(stored("dispatcher")).isEqualTo(first);
  }

  @Test
  void aChangedHashReplacesTheOldOneAtOnce() {
    register(byHash("dispatcher", FIRST, "workspace/read"));
    assertThat(tokenService.validate(FIRST)).isTrue();
    Object created = stored("dispatcher").getCreationDate();

    register(byHash("dispatcher", SECOND, "workspace/read"));

    assertThat(tokenService.validate(SECOND)).isTrue();
    assertThat(tokenService.validate(FIRST)).isFalse();
    assertThat(stored("dispatcher").getCreationDate()).isEqualTo(created);
  }

  @Test
  void changedPermissionsAreAppliedWithoutChangingTheToken() {
    register(byHash("dispatcher", FIRST, "workspace/read"));
    assertThat(tokenService.get(FIRST).getPermissions().get(0).getActions())
        .containsExactly("workspace/read");

    register(byHash("dispatcher", FIRST, "workspace/read", "workflow/read"));

    assertThat(tokenService.get(FIRST).getPermissions().get(0).getActions())
        .containsExactly("workspace/read", "workflow/read");
  }

  @Test
  void aRemovedEntryIsRevokedAndOnlyThatEntry() {
    register(byHash("old", FIRST, "workspace/read"), byHash("new", SECOND, "workspace/read"));
    assertThat(tokenService.validate(FIRST)).isTrue();
    assertThat(tokenService.validate(SECOND)).isTrue();

    register(byHash("new", SECOND, "workspace/read"));

    assertThat(tokenRepository.findById(DeclaredTokenService.ID_PREFIX + "old")).isEmpty();
    assertThat(tokenService.validate(FIRST)).isFalse();
    assertThat(tokenService.validate(SECOND)).isTrue();
  }

  @Test
  void removingEveryEntryRevokesEveryDeclaredTokenButNoOtherToken() {
    register(byHash("one", FIRST, "workspace/read"));
    TokenEntity other = new TokenEntity();
    other.setId("not-declared-" + System.nanoTime());
    other.setType(AuthScope.global);
    other.setToken(tokenService.hashString(SECOND));
    tokenRepository.save(other);

    register();

    assertThat(tokenService.validate(FIRST)).isFalse();
    assertThat(tokenRepository.findById(other.getId())).isPresent();
    tokenRepository.delete(other);
  }

  @Test
  void twoEntriesAreValidAtOnce() {
    register(byHash("current", FIRST, "workspace/read"), byValue("next", SECOND, "workspace/read"));

    assertThat(tokenService.validate(FIRST)).isTrue();
    assertThat(tokenService.validate(SECOND)).isTrue();
  }

  @Test
  void twoEntriesSharingATokenStopTheStartBeforeAnythingIsStored() {
    assertThatThrownBy(
            () -> register(byHash("a", FIRST, "workspace/read"), byValue("b", FIRST, "workspace/read")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'b' declares the same token as another entry");

    assertThat(tokenRepository.findByIdStartingWith(DeclaredTokenService.ID_PREFIX)).isEmpty();
  }

  @Test
  void aDeclaredTokenHoldsOnlyItsDeclaredPermissions() throws Exception {
    register(byValue("reader", FIRST, "workspace/read"));
    SecurityContextHolder.clearContext();

    mockMvc
        .perform(get(WORKSPACE_QUERY).header(HttpHeaders.AUTHORIZATION, "Bearer " + FIRST))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            delete("/api/v2/workspace/declared-token-test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FIRST))
        .andExpect(status().isForbidden());
  }

  @Test
  void aRevokedTokenIsRefusedOnTheWire() throws Exception {
    register(byValue("reader", FIRST, "workspace/read"));
    register();
    SecurityContextHolder.clearContext();

    mockMvc
        .perform(get(WORKSPACE_QUERY).header(HttpHeaders.AUTHORIZATION, "Bearer " + FIRST))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aDeclaredTokenCannotBeDeletedThroughTheTokenService() {
    register(byValue("reader", FIRST, "workspace/read"));

    assertThatThrownBy(() -> tokenService.delete(DeclaredTokenService.ID_PREFIX + "reader"))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getCode()).isEqualTo(BoomerangError.TOKEN_DECLARED.getCode()));
    assertThat(tokenService.validate(FIRST)).isTrue();
  }

  @Test
  void theTokenApiRefusesToDeleteADeclaredToken() throws Exception {
    register(byValue("reader", FIRST, "workspace/read"), byValue("admin", SECOND, "**/**"));
    SecurityContextHolder.clearContext();

    mockMvc
        .perform(
            delete("/api/v2/token/" + DeclaredTokenService.ID_PREFIX + "reader")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECOND))
        .andExpect(status().isConflict());
    assertThat(tokenRepository.findById(DeclaredTokenService.ID_PREFIX + "reader")).isPresent();
  }
}
