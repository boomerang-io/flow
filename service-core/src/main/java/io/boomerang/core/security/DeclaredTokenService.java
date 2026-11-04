package io.boomerang.core.security;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Registers the machine tokens declared in {@code flow.security.tokens}, so a hosted install needs
 * no minting step after its first start. Each entry is a global token stored, like every token, as
 * its hash under the fixed id {@code declared-token:<name>}: a restart changes nothing, instances
 * starting together converge on one record, and a changed entry replaces the stored one. Several
 * entries are valid at once, so a token rotates by adding the new entry before removing the old.
 * A stored declared token whose entry is gone is deleted. It runs whether or not security is on.
 */
@Service
public class DeclaredTokenService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final String ID_PREFIX = "declared-token:";

  private final DeclaredTokenProperties properties;
  private final TokenService tokenService;
  private final TokenRepository tokenRepository;
  private final TokenLookupCache lookupCache;

  public DeclaredTokenService(
      DeclaredTokenProperties properties,
      TokenService tokenService,
      TokenRepository tokenRepository,
      TokenLookupCache lookupCache) {
    this.properties = properties;
    this.tokenService = tokenService;
    this.tokenRepository = tokenRepository;
    this.lookupCache = lookupCache;
  }

  /**
   * Upsert every declared token, then revoke the stored ones no entry names. Throw when two entries
   * share a token, so the start stops rather than one entry silently shadowing another.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void register() {
    List<TokenEntity> declared = properties.tokens().stream().map(this::toEntity).toList();
    Set<String> hashes = new HashSet<>();
    declared.forEach(
        entity -> {
          if (!hashes.add(entity.getToken())) {
            throw new IllegalStateException(
                "flow.security.tokens: '" + entity.getName() + "' declares the same token as another entry.");
          }
        });
    declared.forEach(this::upsert);

    Set<String> ids = new HashSet<>();
    declared.forEach(entity -> ids.add(entity.getId()));
    tokenRepository.findByIdStartingWith(ID_PREFIX).stream()
        .filter(stored -> !ids.contains(stored.getId()))
        .forEach(
            stored -> {
              tokenRepository.delete(stored);
              lookupCache.evict(stored.getToken());
              LOGGER.info("Revoked the declared token '{}': it is no longer configured.", stored.getName());
            });
  }

  private void upsert(TokenEntity entity) {
    TokenEntity previous = tokenRepository.findById(entity.getId()).orElse(null);
    if (previous != null) {
      entity.setCreationDate(previous.getCreationDate());
      entity.setLastUsedAt(previous.getLastUsedAt());
      if (entity.equals(previous)) {
        return;
      }
    }
    try {
      tokenRepository.save(entity);
    } catch (DuplicateKeyException e) {
      // Another instance registered it in the same moment; the record is the same either way.
      return;
    }
    if (previous != null) {
      // A changed entry: the old hash stops working here at once, elsewhere within the cache TTL.
      lookupCache.evict(previous.getToken());
    }
    LOGGER.info(
        "Registered the declared token '{}' ({}).",
        entity.getName(),
        previous == null ? "new" : "replaced the previous");
  }

  private TokenEntity toEntity(DeclaredTokenProperties.Entry entry) {
    TokenEntity entity = new TokenEntity();
    entity.setId(ID_PREFIX + entry.name());
    entity.setType(AuthScope.global);
    entity.setName(entry.name());
    entity.setDescription(entry.description());
    entity.setActorKind(entry.actorKind());
    Instant expiresAt = entry.expiresAt();
    entity.setExpirationDate(expiresAt == null ? null : Date.from(expiresAt));
    entity.setPermissions(
        new LinkedList<>(
            List.of(new ResolvedPermissions(PermissionScope.global, "**", entry.permissions()))));
    entity.setToken(
        entry.hash() != null ? entry.hash() : tokenService.hashString(entry.value()));
    return entity;
  }
}
