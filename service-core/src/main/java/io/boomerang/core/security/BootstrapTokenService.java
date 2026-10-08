package io.boomerang.core.security;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Registers the operator-supplied {@code flow.security.bootstrap-token}, so an engine that accepts
 * only Flow-minted tokens can be reached before anyone has minted one: it is the token that mints
 * the rest. It is a global admin machine token stored, like every token, as its hash - under one
 * fixed id, so a restart changes nothing, instances starting together converge on one record, and
 * a new value replaces the old one. Blank registers nothing.
 */
@Service
public class BootstrapTokenService {

  private static final Logger LOGGER = LogManager.getLogger();

  static final String BOOTSTRAP_TOKEN_ID = "bootstrap";

  // "bfg_" and at least 32 more characters: a global token's shape, and too long to guess.
  private static final String BOOTSTRAP_TOKEN_PATTERN = "^bfg_.{32,}$";

  private final String bootstrapToken;
  private final TokenService tokenService;
  private final TokenRepository tokenRepository;
  private final TokenLookupCache lookupCache;

  public BootstrapTokenService(
      @Value("${flow.security.bootstrap-token:}") String bootstrapToken,
      TokenService tokenService,
      TokenRepository tokenRepository,
      TokenLookupCache lookupCache) {
    this.bootstrapToken = bootstrapToken;
    this.tokenService = tokenService;
    this.tokenRepository = tokenRepository;
    this.lookupCache = lookupCache;
  }

  /**
   * Store the bootstrap token's hash unless it is already stored. Throw when the value is not a
   * {@code bfg_} token of at least 32 further characters, so a weak or mistyped secret stops the
   * start rather than opening the engine.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void register() {
    if (!StringUtils.hasText(bootstrapToken)) {
      return;
    }
    if (!bootstrapToken.matches(BOOTSTRAP_TOKEN_PATTERN)) {
      throw new IllegalStateException(
          "flow.security.bootstrap-token must be bfg_ followed by at least 32 characters.");
    }
    String hash = tokenService.hashString(bootstrapToken);
    TokenEntity previous = tokenRepository.findById(BOOTSTRAP_TOKEN_ID).orElse(null);
    if (previous != null && hash.equals(previous.getToken())) {
      return;
    }
    try {
      tokenRepository.save(bootstrapEntity(hash));
    } catch (DuplicateKeyException e) {
      // Another instance registered it in the same moment; the record is the same either way.
      return;
    }
    if (previous != null) {
      // A rotated value: the old one stops working here at once, elsewhere within the cache TTL.
      lookupCache.evict(previous.getToken());
    }
    LOGGER.info(
        "Registered the bootstrap token ({}).", previous == null ? "new" : "replaced the previous");
  }

  private static TokenEntity bootstrapEntity(String hash) {
    TokenEntity entity = new TokenEntity();
    entity.setId(BOOTSTRAP_TOKEN_ID);
    entity.setType(AuthScope.global);
    entity.setName("bootstrap");
    entity.setDescription("Registered at startup from flow.security.bootstrap-token.");
    entity.setActorKind(TokenActorKind.SERVICE);
    entity.setCreationDate(new Date());
    entity.setPermissions(
        new LinkedList<>(
            List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**")))));
    entity.setToken(hash);
    return entity;
  }
}
