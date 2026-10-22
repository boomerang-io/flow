package io.boomerang.core.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.boomerang.core.entity.TokenEntity;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Positive-only, per-instance cache of validated tokens keyed by the stored SHA-256 hash, so the
 * authentication path resolves a bearer with one repository read per TTL instead of one per call.
 * Only entities that were present and unexpired are ever stored - misses are never cached, so an
 * invalid-token flood cannot fill the map - and callers re-check {@code expirationDate} on every
 * hit because expiry is a property of the token, not of the cache. Eviction is per instance:
 * another instance keeps serving a revoked token until its own entry ages out (the TTL).
 */
@Component
public class TokenLookupCache {

  private final boolean enabled;
  private final Cache<String, TokenEntity> cache;

  public TokenLookupCache(
      @Value("${flow.security.token-cache.enabled:true}") boolean enabled,
      @Value("${flow.security.token-cache.ttl:60s}") Duration ttl,
      @Value("${flow.security.token-cache.max-size:10000}") long maxSize) {
    this.enabled = enabled;
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
  }

  /** Return the cached entity for {@code hash}, or null when absent, aged out, or disabled. */
  public TokenEntity get(String hash) {
    return (enabled ? cache.getIfPresent(hash) : null);
  }

  /** Remember a validated entity under its hash; a no-op when disabled. */
  public void put(String hash, TokenEntity entity) {
    if (enabled) {
      cache.put(hash, entity);
    }
  }

  /** Forget one hash - called on every write that changes a token's validity or grants. */
  public void evict(String hash) {
    if (hash != null) {
      cache.invalidate(hash);
    }
  }

  /** Forget every entry - for bulk revocations where the affected hashes are not in hand. */
  public void evictAll() {
    cache.invalidateAll();
  }
}
