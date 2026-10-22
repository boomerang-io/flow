package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.TokenLookupCache;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lookup cache as wired into the Spring context against a real Mongo: a minted token
 * validates, is cached under its hash, and revocation through {@code TokenService.delete} both
 * removes the document and evicts the entry so the very next validation is refused.
 */
class TokenServiceLookupCacheWiringTest extends AbstractEngineIntegrationTest {

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private TokenLookupCache lookupCache;

  @Test
  void aRevokedTokenIsRefusedOnTheNextRequest() {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("cache-wiring-" + UUID.randomUUID());
    request.setPrincipal("workflow-" + UUID.randomUUID());
    request.setPermissions(List.of("workflow/read"));
    TokenCreateResponse created = tokenService.create(request);
    String raw = created.getToken();
    String hash = tokenService.hashString(raw);

    assertThat(tokenService.validate(raw)).isTrue();
    assertThat(lookupCache.get(hash)).isNotNull();
    Token token = tokenService.get(raw);
    assertThat(token.getPrincipal()).isEqualTo(request.getPrincipal());

    assertThat(tokenService.delete(created.getId())).isTrue();

    assertThat(lookupCache.get(hash)).isNull();
    assertThat(tokenRepository.findByToken(hash)).isEmpty();
    assertThat(tokenService.validate(raw)).isFalse();
    assertThat(tokenService.get(raw)).isNull();
  }
}
