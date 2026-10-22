package io.boomerang.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.repository.ActionRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

/**
 * framework-review-proposals.md A10: {@code TokenService.query}'s {@code getPage} supplier counted
 * {@code ActionEntity.class} instead of {@code TokenEntity.class} - always wrong, not just on a
 * full page, because it counts a different collection entirely. Also covers the same
 * skip/limit-reset fix as the other 8 sites (a full page of tokens must still report the true
 * total, not just the page size).
 *
 * <p>Seeds 3 tokens for one principal AND 5 unrelated actions in the same shared Testcontainers
 * Mongo, so a count that leaked onto {@code actions} (5, or 3+5=8) is distinguishable from the
 * correct count (3).
 */
class TokenQueryCountsTokensNotActionsTest extends AbstractEngineIntegrationTest {

  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private ActionRepository actionRepository;

  @Test
  void queryCountsOnlyTokensEvenWhenMoreActionsExist() {
    String principal = "token-count-principal-" + UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      TokenEntity token = new TokenEntity();
      token.setType(AuthScope.session);
      token.setName("token-" + i);
      token.setPrincipal(principal);
      token.setCreationDate(new Date());
      tokenRepository.save(token);
    }
    // More actions than tokens, in an entirely different collection - if the count ever falls
    // back to (or leaks into) ActionEntity, this makes it obviously wrong (5, not 3).
    for (int i = 0; i < 5; i++) {
      ActionEntity action = new ActionEntity();
      action.setWorkflowRef("token-count-unrelated-workflow-" + UUID.randomUUID());
      action.setCreationDate(new Date());
      actionRepository.save(action);
    }

    Page<Token> page =
        tokenService.query(
            Optional.empty(),
            Optional.empty(),
            Optional.of(10),
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of(principal)));

    assertEquals(3, page.getTotalElements(), "must count the 3 tokens, not the 5 actions");
    assertEquals(3, page.getContent().size());
  }
}
