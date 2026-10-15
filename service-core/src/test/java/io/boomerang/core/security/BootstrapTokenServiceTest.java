package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
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
 * The bootstrap token is registered on start, survives a restart unchanged, is replaced when the
 * operator rotates it, refuses a value too weak to be a global token, and authenticates a request
 * like any other global service token.
 */
class BootstrapTokenServiceTest extends AbstractEngineIntegrationTest {

  private static final String FIRST = "bfg_" + "a".repeat(40);
  private static final String SECOND = "bfg_" + "b".repeat(40);
  private static final String SHAPE_RULE =
      "flow.security.bootstrap-token must be bfg_ followed by at least 32 characters.";

  // Admits a global token; the bootstrap token's grant is **/**.
  private static final String WORKSPACE_QUERY = "/api/v2/workspace/query";

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private TokenLookupCache lookupCache;
  @Autowired private WebApplicationContext context;

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
  void removeBootstrapToken() {
    tokenRepository.deleteById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID);
    lookupCache.evictAll();
  }

  private BootstrapTokenService bootstrapToken(String value) {
    return new BootstrapTokenService(value, tokenService, tokenRepository, lookupCache);
  }

  @Test
  void theBootstrapTokenIsRegisteredOnStartAsAGlobalServiceToken() {
    bootstrapToken(FIRST).register();

    assertThat(tokenService.validate(FIRST)).isTrue();
    TokenEntity stored =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();
    assertThat(stored.getType()).isEqualTo(AuthScope.global);
    assertThat(stored.getActorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(stored.getToken()).isNotEqualTo(FIRST);
  }

  @Test
  void registeringTheSameValueAgainChangesNothing() {
    bootstrapToken(FIRST).register();
    TokenEntity first =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();

    bootstrapToken(FIRST).register();

    TokenEntity again =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();
    assertThat(again.getCreationDate()).isEqualTo(first.getCreationDate());
  }

  @Test
  void rotatingTheValueEvictsTheOldHash() {
    bootstrapToken(FIRST).register();
    assertThat(tokenService.validate(FIRST)).isTrue();

    bootstrapToken(SECOND).register();

    assertThat(tokenService.validate(SECOND)).isTrue();
    assertThat(tokenService.validate(FIRST)).isFalse();
    assertThat(tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID))
        .get()
        .extracting(TokenEntity::getToken)
        .isEqualTo(tokenService.hashString(SECOND));
  }

  @Test
  void aShortValueStopsTheStartWithTheShapeRule() {
    assertThatThrownBy(() -> bootstrapToken("bfg_short").register())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(SHAPE_RULE);
  }

  @Test
  void aWronglyPrefixedValueStopsTheStartWithTheShapeRule() {
    assertThatThrownBy(() -> bootstrapToken("bfk_" + "a".repeat(40)).register())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(SHAPE_RULE);
  }

  @Test
  void aBlankValueRegistersNothing() {
    bootstrapToken("").register();

    assertThat(tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID)).isEmpty();
  }

  /**
   * The registered token is an ordinary global service token on the wire. That the profile refuses
   * every global token, this one included, is pinned by {@link
   * EndpointAuthorizationResponseTest#aTokenKindTheEndpointDoesNotAcceptIsUnauthorized()}.
   */
  @Test
  void theRegisteredTokenAuthenticatesARequestToAWorkspaceRoute() throws Exception {
    bootstrapToken(FIRST).register();
    SecurityContextHolder.clearContext();

    mockMvc
        .perform(get(WORKSPACE_QUERY).header(HttpHeaders.AUTHORIZATION, "Bearer " + FIRST))
        .andExpect(status().isOk());
  }
}
