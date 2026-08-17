package io.boomerang.core.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;

public interface TokenRepository extends MongoRepository<TokenEntity, String> {
  Optional<TokenEntity> findByToken(String token);

  Optional<List<TokenEntity>> findByPrincipalAndType(String principal, AuthScope type);

  // T6-3: the retired `workflow` token class folded into `key` + actorKind=WORKFLOW - callers
  // that used to filter on the (now-gone) workflow AuthScope value narrow with this instead.
  Optional<List<TokenEntity>> findByPrincipalAndTypeAndActorKind(
      String principal, AuthScope type, TokenActorKind actorKind);

  void deleteAllByPrincipal(String principal);
}
