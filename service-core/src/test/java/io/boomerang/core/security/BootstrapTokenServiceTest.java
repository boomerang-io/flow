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
 * The bootstrap token is registered once, survives restarts unchanged, is replaced when the
 * operator supplies a new value, and refuses a value too weak to be a global token.
 */
class BootstrapTokenServiceTest extends AbstractEngineIntegrationTest {

  private static final String FIRST = "bfg_" + "a".repeat(40);
  private static final String SECOND = "bfg_" + "b".repeat(40);

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private TokenLookupCache lookupCache;

  @AfterEach
  void removeBootstrapToken() {
    tokenRepository.deleteById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID);
    lookupCache.evictAll();
  }

  private BootstrapTokenService bootstrap(String value) {
    return new BootstrapTokenService(value, tokenService, tokenRepository, lookupCache);
  }

  @Test
  void theBootstrapTokenAuthenticatesAsAGlobalServiceToken() {
    bootstrap(FIRST).register();

    assertThat(tokenService.validate(FIRST)).isTrue();
    TokenEntity stored =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();
    assertThat(stored.getType()).isEqualTo(AuthScope.global);
    assertThat(stored.getActorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(stored.getToken()).isNotEqualTo(FIRST);
  }

  @Test
  void registeringAgainChangesNothing() {
    bootstrap(FIRST).register();
    TokenEntity first =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();

    bootstrap(FIRST).register();

    TokenEntity again =
        tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID).orElseThrow();
    assertThat(again.getCreationDate()).isEqualTo(first.getCreationDate());
  }

  @Test
  void aNewValueReplacesTheOldOne() {
    bootstrap(FIRST).register();
    assertThat(tokenService.validate(FIRST)).isTrue();

    bootstrap(SECOND).register();

    assertThat(tokenService.validate(SECOND)).isTrue();
    assertThat(tokenService.validate(FIRST)).isFalse();
  }

  @Test
  void aWeakValueStopsTheStart() {
    assertThrows(IllegalStateException.class, () -> bootstrap("bfg_short").register());
    assertThrows(
        IllegalStateException.class, () -> bootstrap("bfk_" + "a".repeat(40)).register());
  }

  @Test
  void aBlankValueRegistersNothing() {
    bootstrap("").register();

    assertThat(tokenRepository.findById(BootstrapTokenService.BOOTSTRAP_TOKEN_ID)).isEmpty();
  }
}
