package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The engine token is registered once, survives restarts unchanged, is replaced when the
 * operator supplies a new value, and refuses a value too weak to be a global token.
 */
class EngineTokenServiceTest extends AbstractEngineIntegrationTest {

  private static final String FIRST = "bfg_" + "a".repeat(40);
  private static final String SECOND = "bfg_" + "b".repeat(40);

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private TokenLookupCache lookupCache;

  @AfterEach
  void removeEngineToken() {
    tokenRepository.deleteById(EngineTokenService.ENGINE_TOKEN_ID);
    lookupCache.evictAll();
  }

  private EngineTokenService engineToken(String value) {
    return new EngineTokenService(value, tokenService, tokenRepository, lookupCache);
  }

  @Test
  void theEngineTokenAuthenticatesAsAGlobalServiceToken() {
    engineToken(FIRST).register();

    assertThat(tokenService.validate(FIRST)).isTrue();
    TokenEntity stored =
        tokenRepository.findById(EngineTokenService.ENGINE_TOKEN_ID).orElseThrow();
    assertThat(stored.getType()).isEqualTo(AuthScope.global);
    assertThat(stored.getActorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(stored.getToken()).isNotEqualTo(FIRST);
  }

  @Test
  void registeringAgainChangesNothing() {
    engineToken(FIRST).register();
    TokenEntity first =
        tokenRepository.findById(EngineTokenService.ENGINE_TOKEN_ID).orElseThrow();

    engineToken(FIRST).register();

    TokenEntity again =
        tokenRepository.findById(EngineTokenService.ENGINE_TOKEN_ID).orElseThrow();
    assertThat(again.getCreationDate()).isEqualTo(first.getCreationDate());
  }

  @Test
  void aNewValueReplacesTheOldOne() {
    engineToken(FIRST).register();
    assertThat(tokenService.validate(FIRST)).isTrue();

    engineToken(SECOND).register();

    assertThat(tokenService.validate(SECOND)).isTrue();
    assertThat(tokenService.validate(FIRST)).isFalse();
  }

  @Test
  void aWeakValueStopsTheStart() {
    assertThrows(IllegalStateException.class, () -> engineToken("bfg_short").register());
    assertThrows(
        IllegalStateException.class, () -> engineToken("bfk_" + "a".repeat(40)).register());
  }

  @Test
  void aBlankValueRegistersNothing() {
    engineToken("").register();

    assertThat(tokenRepository.findById(EngineTokenService.ENGINE_TOKEN_ID)).isEmpty();
  }
}
