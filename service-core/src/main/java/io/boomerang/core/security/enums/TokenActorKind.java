package io.boomerang.core.security.enums;

/**
 * Orthogonal discriminator for who/what a token represents, layered on top of {@link AuthScope}
 * rather than a new scope value or token prefix (T6-1 — least deviation from ARCHIE's proven
 * model: {@code AuthScope}/{@code TokenTypePrefix} stay exactly as they are; this field is the
 * only addition).
 *
 * <p>Null on every pre-existing/human token (session/user-driven) — only a machine-minted API
 * token sets it. The dispatcher token (T6-1) is an existing {@code global} ({@code bfg_}) token
 * with {@code actorKind = SERVICE}; no {@code dispatcher} value is added here — "Agent" stays
 * reserved for the AI task types (DD-06), so a worker-tier credential is a {@code SERVICE}, not
 * an {@code AGENT}. {@code AGENT} is carried over from ARCHIE for the same future purpose it
 * serves there (a distinctly-badged AI-driven actor identity) — not used by this track.
 */
public enum TokenActorKind {
  /** A system/integration/worker (CI, script, backend service, dispatcher). */
  SERVICE,
  /** An AI agent acting on a delegator's behalf — badged distinctly in audit + UI. */
  AGENT
}
