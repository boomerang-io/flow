package io.boomerang.core.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserStatus;

/**
 * <b>Email lookups are exact-match, not {@code IgnoreCase}.</b> {@code users.email} is stored
 * already lower-cased (every write goes through {@code UserService}, which lower-cases with {@code
 * Locale.ROOT}), so an equality predicate can seek the {@code users.email_lookup} index built by
 * {@code _0036__RelationshipAndAuditIndexes}. The previous {@code ...IgnoreCase} derivations were
 * rendered by Spring Data as an {@code $options:'i'} regex, for which MongoDB cannot compute index
 * bounds — every one of them was a full index scan.
 *
 * <p>Callers MUST lower-case the value before calling: user-supplied input (a login form, an
 * {@code x-forwarded-email} header, an API request body) arrives in whatever case the caller typed.
 * {@code UserService} is the only caller and does exactly that.
 */
public interface UserRepository extends MongoRepository<UserEntity, String> {

  Long countByEmailAndStatus(String email, UserStatus status);

  Optional<UserEntity> findByIdAndStatus(String id, UserStatus status);

  UserEntity findByEmail(String email);

  UserEntity findByEmailAndStatus(String email, UserStatus status);

  /**
   * Substring search across name and email for a typeahead — deliberately still case-insensitive.
   * A {@code Like} predicate is a regex either way and can never seek an index, and the {@code
   * name} half has no normalised form at all, so making the email half case-sensitive would only
   * regress the search.
   */
  Page<UserEntity> findByNameLikeIgnoreCaseOrEmailLikeIgnoreCase(
      String term, String term2, Pageable pageable);
}
