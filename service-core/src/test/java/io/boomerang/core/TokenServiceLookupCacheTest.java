package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.TokenLookupCache;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The token lookup cache in front of {@code TokenRepository.findByToken}: the authentication
 * filter's {@code validate} + {@code get} pair costs one repository read, misses are never
 * cached, revocation evicts, an expired token is refused even while cached, and the switch turns
 * the cache into a pass-through.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceLookupCacheTest {

  private static final String RAW = "bfu_11111111-2222-3333-4444-555555555555";

  @Mock private TokenRepository tokenRepository;
  @Mock private UserService userService;
  @Mock private RoleRepository roleRepository;
  @Mock private RelationshipService relationshipService;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private IdentityService identityService;

  private TokenService tokenService;
  private String hash;
  private TokenEntity stored;

  @BeforeEach
  void setUp() {
    tokenService = service(new TokenLookupCache(true, Duration.ofSeconds(60), 10_000));
    hash = tokenService.hashString(RAW);
    stored = new TokenEntity();
    stored.setId("token-1");
    stored.setToken(hash);
    stored.setType(AuthScope.user);
    stored.setPrincipal("user-1");
    stored.setExpirationDate(new Date(System.currentTimeMillis() + 60_000));
  }

  private TokenService service(TokenLookupCache cache) {
    return new TokenService(
        tokenRepository,
        userService,
        roleRepository,
        relationshipService,
        mongoTemplate,
        identityService,
        cache);
  }

  @Test
  void validateThenGetReadsTheRepositoryOnce() {
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isTrue();
    assertThat(tokenService.get(RAW)).isNotNull().extracting("principal").isEqualTo("user-1");
    assertThat(tokenService.validate(RAW)).isTrue();

    verify(tokenRepository, times(1)).findByToken(hash);
  }

  @Test
  void anActorTokenIsServedFromTheSameCacheEntry() {
    stored.setType(AuthScope.global);
    stored.setActorKind(TokenActorKind.SERVICE);
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isTrue();
    assertThat(tokenService.validateActorToken(RAW)).contains(stored);

    verify(tokenRepository, times(1)).findByToken(hash);
  }

  @Test
  void anUnknownTokenIsLookedUpEveryTime() {
    when(tokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

    assertThat(tokenService.validate(RAW)).isFalse();
    assertThat(tokenService.get(RAW)).isNull();
    assertThat(tokenService.validate("bfu_unknown")).isFalse();

    verify(tokenRepository, times(3)).findByToken(anyString());
  }

  @Test
  void anExpiredTokenReadFromTheRepositoryIsNotCached() {
    stored.setExpirationDate(new Date(System.currentTimeMillis() - 1_000));
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isFalse();
    assertThat(tokenService.validate(RAW)).isFalse();

    verify(tokenRepository, times(2)).findByToken(hash);
  }

  @Test
  void deleteEvictsSoTheNextLookupMisses() {
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored), Optional.empty());
    when(tokenRepository.findById("token-1")).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isTrue();
    assertThat(tokenService.delete("token-1")).isTrue();
    assertThat(tokenService.validate(RAW)).isFalse();

    verify(tokenRepository).delete(stored);
    verify(tokenRepository, times(2)).findByToken(hash);
  }

  @Test
  void deleteAllForPrincipalEvictsSoTheNextLookupMisses() {
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored), Optional.empty());

    assertThat(tokenService.validate(RAW)).isTrue();
    tokenService.deleteAllForPrincipal("user-1");
    assertThat(tokenService.validate(RAW)).isFalse();

    verify(tokenRepository).deleteAllByPrincipal("user-1");
    verify(tokenRepository, times(2)).findByToken(hash);
  }

  @Test
  void aCachedTokenThatHasSinceExpiredIsRejected() {
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));
    assertThat(tokenService.validate(RAW)).isTrue();

    // Expiry is a property of the token, so the cached entity is re-checked on every hit.
    stored.setExpirationDate(new Date(System.currentTimeMillis() - 1_000));

    assertThat(tokenService.validate(RAW)).isFalse();
    assertThat(tokenService.get(RAW).isValid()).isFalse();
    assertThat(tokenService.validateActorToken(RAW)).isEmpty();
    verify(tokenRepository, never()).delete(stored);
  }

  @Test
  void touchLastUsedKeepsTheEntryCached() {
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isTrue();
    tokenService.touchLastUsed(stored);
    assertThat(tokenService.validate(RAW)).isTrue();

    verify(tokenRepository).save(stored);
    verify(tokenRepository, times(1)).findByToken(hash);
  }

  @Test
  void aDisabledCacheReadsTheRepositoryOnEveryCall() {
    tokenService = service(new TokenLookupCache(false, Duration.ofSeconds(60), 10_000));
    when(tokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));

    assertThat(tokenService.validate(RAW)).isTrue();
    assertThat(tokenService.get(RAW)).isNotNull();

    verify(tokenRepository, times(2)).findByToken(hash);
  }
}
