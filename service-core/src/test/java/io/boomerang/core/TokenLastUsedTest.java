package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A token's "last used" stamp writes that one field. The token handed in is usually a cached copy,
 * so a whole-document save would bring back a token deleted since it was cached.
 */
class TokenLastUsedTest extends AbstractEngineIntegrationTest {

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;

  @Test
  void theStampWritesOnlyLastUsedAt() {
    TokenEntity token = saved("last-used-stamp");
    token.setName("changed only in this copy");

    tokenService.touchLastUsed(token);

    TokenEntity stored = tokenRepository.findById(token.getId()).orElseThrow();
    assertThat(stored.getLastUsedAt()).isNotNull();
    assertThat(stored.getName()).isEqualTo("last-used-stamp");
  }

  @Test
  void aTokenDeletedSinceItWasCachedStaysDeleted() {
    TokenEntity cached = saved("last-used-deleted");
    tokenRepository.deleteById(cached.getId());

    tokenService.touchLastUsed(cached);

    assertThat(tokenRepository.findById(cached.getId())).isEmpty();
  }

  private TokenEntity saved(String name) {
    TokenEntity token = new TokenEntity();
    token.setName(name);
    token.setType(AuthScope.global);
    token.setToken("hash-" + name);
    return tokenRepository.save(token);
  }
}
