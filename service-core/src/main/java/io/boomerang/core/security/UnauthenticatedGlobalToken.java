package io.boomerang.core.security;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.List;

/**
 * The synthetic identity installed on the {@code SecurityContext} when {@code
 * flow.security.enabled=false} (the documented local-dev/E2E posture, and the default for {@code
 * flow.mode=engine}).
 *
 * <p><b>Why this exists.</b> Before this type, nothing populated the {@code SecurityContext} with
 * security off - {@code AuthenticationFilter} is {@code @ConditionalOnProperty} on that flag and
 * never loads - so {@link io.boomerang.core.security.IdentityService#getCurrentIdentity()} returned
 * {@code null} and every caller invented its own meaning for "nobody is here". They disagreed:
 * {@code RelationshipService.check()} allowed unscoped, {@code filter()} anchored at {@code ROOT},
 * {@code WorkspaceActionService} bypassed approver-group membership, and {@code AuditInterceptor}
 * threw inside {@code new AuditActor(token)} - so <b>no audit record was ever written at all</b>
 * with security off.
 *
 * <p>This follows Spring Security's own rationale for {@code AnonymousAuthenticationToken}:
 * "classes can be authored more robustly if they know the SecurityContextHolder always contains an
 * Authentication object, and never null" - explicitly citing auditing interceptors. Kubernetes does
 * the same, assigning {@code system:anonymous} / {@code system:unauthenticated} to unauthenticated
 * callers. Engine mode is a machine caller with no user; this token is exactly that caller.
 *
 * <p><b>Shape.</b> A plain {@link Token} subclass, not a parallel type hierarchy - every existing
 * consumer ({@code check}/{@code filter}/{@code AuditActor}/{@code resolveGrantCeiling}) keeps
 * working through the {@link Token} API it already uses. The values are chosen to reproduce the
 * previous no-principal behaviour exactly rather than to widen or narrow it:
 *
 * <ul>
 *   <li>{@link AuthScope#global} - {@code check()}'s {@code case global} returns {@code true} and
 *       {@code filter()}'s {@code case global} anchors at {@code ROOT}, which is precisely what the
 *       deleted {@code identity == null} branches in both methods did.
 *   <li>{@link #PRINCIPAL} {@code = "system"} - a non-null, clearly identifiable actor, so audit
 *       records are written with {@code actor.principal = "system"} instead of the write throwing.
 *   <li>A single {@code global}-scoped {@code **}/{@code **} grant - reproduces {@code
 *       TokenService.resolveGrantCeiling()}'s no-principal branch ({@code Set.of("**&#47;**")}) and
 *       keeps {@code checkPermissions()} passing, so the {@code flow.security.would.deny} shadow
 *       counter is not polluted by an instance that has authorization switched off entirely.
 *   <li>{@link TokenActorKind#SERVICE} - "a system/integration/worker", badged distinctly in audit
 *       and UI.
 * </ul>
 *
 * <p><b>This token is never persisted and never minted for a caller.</b> It has no {@code id}, so
 * {@code AuditActor.tokenRef} stays null and {@code AuthExchangeService.logout()} cannot try to
 * revoke it. It is constructed per request by {@link UnauthenticatedGlobalAuthenticationFilter} and
 * only ever installed when security is disabled - when security is enabled this class is never
 * instantiated and {@code AuthenticationFilter}'s real-token path is untouched.
 */
public class UnauthenticatedGlobalToken extends Token {

  /** The actor recorded in audit records for an instance running with security disabled. */
  public static final String PRINCIPAL = "system";

  public UnauthenticatedGlobalToken() {
    super(AuthScope.global);
    this.setName(PRINCIPAL);
    this.setPrincipal(PRINCIPAL);
    this.setValid(true);
    this.setActorKind(TokenActorKind.SERVICE);
    this.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
  }
}
