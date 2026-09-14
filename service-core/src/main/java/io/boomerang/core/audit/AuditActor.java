package io.boomerang.core.audit;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.UnauthenticatedGlobalToken;

/**
 * Who performed an audited operation, resolved from the current {@link Token}.
 *
 * <p>{@code type} is the token class label ({@code session}, {@code user}, {@code key}, {@code
 * global}); a machine-minted token records its {@code TokenActorKind} instead ({@code service} —
 * which includes the dispatcher credential — {@code agent}, {@code workflow}). The security-off
 * synthetic admin ({@link UnauthenticatedGlobalToken}) records as actor {@code system} so an
 * unsecured instance's trail is clearly badged.
 */
public record AuditActor(String id, String name, String type, String tokenRef) {

  public static final String SYSTEM = "system";
  public static final String ANONYMOUS = "anonymous";

  public static AuditActor from(Token token) {
    if (token == null) {
      return null;
    }
    if (token instanceof UnauthenticatedGlobalToken) {
      return new AuditActor(SYSTEM, SYSTEM, SYSTEM, null);
    }
    String type =
        (token.getActorKind() != null)
            ? token.getActorKind().name().toLowerCase()
            : (token.getType() != null ? token.getType().getLabel() : null);
    return new AuditActor(token.getPrincipal(), token.getName(), type, token.getId());
  }

  public static AuditActor anonymous() {
    return new AuditActor(ANONYMOUS, ANONYMOUS, ANONYMOUS, null);
  }

  public static AuditActor system() {
    return new AuditActor(SYSTEM, SYSTEM, SYSTEM, null);
  }
}
