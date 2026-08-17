package io.boomerang.core.security.enums;

/**
 * Orthogonal discriminator for who/what a {@code key}/{@code global} token represents, layered on
 * top of {@link AuthScope} rather than a new scope value or token prefix (T6-1 — least deviation
 * from ARCHIE's proven model).
 *
 * <p>Null on every human/pre-existing token (session/user-driven) — only a machine-minted token
 * sets it. The dispatcher token (T6-1) is an existing {@code global} ({@code bfg_}) token with
 * {@code actorKind = SERVICE}; no {@code dispatcher} value is added here — "Agent" stays reserved
 * for the AI task types (DD-06), so a worker-tier credential is a {@code SERVICE}, not an {@code
 * AGENT}.
 *
 * <p>{@code WORKFLOW} (T6-3) badges a {@code key} token minted for a Workflow's own use (the
 * scheduled-job token {@code TokenService#createWorkflowSchedulerToken} mints) — {@code
 * principal} holds the workflow id. This is the direct replacement for the retired {@code
 * workflow} token class / {@code bfw} prefix: same shape (one token, one workflow principal), now
 * expressed as {@code AuthScope.key} + this discriminator instead of its own top-level class.
 */
public enum TokenActorKind {
  /** A system/integration/worker (CI, script, backend service, dispatcher). */
  SERVICE,
  /** An AI agent acting on a delegator's behalf — badged distinctly in audit + UI. */
  AGENT,
  /** A Workflow's own scheduled-job credential — {@code principal} is the workflow id. */
  WORKFLOW
}
