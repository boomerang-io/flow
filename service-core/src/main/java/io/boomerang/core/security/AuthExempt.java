package io.boomerang.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The explicit opt-out from {@link AuthCriteria}. Every REST handler MUST carry one or the other:
 * {@link AuthCriteriaAuthorizationManager} denies a handler that has neither, so a route can no
 * longer be published without an authorization decision having been made for it. Placed on a
 * method it exempts that route; placed on a class it exempts every route of that controller (a
 * method-level {@code @AuthCriteria} still wins for that method).
 *
 * <p>Exempt means "authorization is decided elsewhere or not required", never "no security": the
 * filter chain still runs. The cases: routes that are public by design (the sign-in exchange, the
 * auth config a browser reads before it has a session), inbound callbacks whose caller cannot hold
 * a Flow token (identity-provider and GitHub callbacks, Slack webhooks verified by signature), and
 * the dispatcher wire, whose identity is a dispatcher actor token checked by its own filter chain.
 *
 * <p>{@code reason} is mandatory and is the review record: say why this route needs no {@code
 * AuthCriteria}, not that it does not have one.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthExempt {
  String reason();
}
