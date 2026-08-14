package io.boomerang.core.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserStatus;

public interface UserRepository extends MongoRepository<UserEntity, String> {

  Long countByEmailIgnoreCaseAndStatus(String email, UserStatus status);

  Optional<UserEntity> findByIdAndStatus(String id, UserStatus status);

  UserEntity findByEmailIgnoreCase(String email);

  UserEntity findByEmailIgnoreCaseAndStatus(String email, UserStatus status);

  Page<UserEntity> findByNameLikeIgnoreCaseOrEmailLikeIgnoreCase(
      String term, String term2, Pageable pageable);
}
