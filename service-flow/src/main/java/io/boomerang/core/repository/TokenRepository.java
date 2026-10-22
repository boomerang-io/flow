package io.boomerang.core.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.security.enums.AuthScope;

public interface TokenRepository extends MongoRepository<TokenEntity, String> {
  Optional<TokenEntity> findByToken(String token);

  Optional<List<TokenEntity>> findByPrincipalAndType(String principal, AuthScope type);

  void deleteAllByPrincipal(String principal);
}
