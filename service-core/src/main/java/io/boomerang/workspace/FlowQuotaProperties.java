package io.boomerang.workspace;

import io.boomerang.config.FlowMode;
import org.springframework.core.env.Environment;

/**
 * Whether the workspace quota subsystem is active, resolved the same way {@link
 * io.boomerang.core.security.FlowSecurityProperties} resolves security: the single {@code
 * flow.quotas.enabled} property, whose default derives from {@code flow.mode} - {@code standalone}
 * = enabled (today's behaviour), {@code engine} = disabled.
 *
 * <p>Quotas are a workspace feature: every limit is read off {@link WorkspaceService}, which loads
 * only in {@code standalone} ({@code @ConditionalOnFlowMode(STANDALONE)}). Engine mode serves the
 * single {@code system} workspace and has no quota record to enforce against, so the subsystem is
 * off there and callers fall back to the platform defaults in the {@code workspaces} settings
 * document ({@link WorkspaceService#WORKSPACES_SETTINGS_KEY}).
 *
 * <p>This is the coarse "does the subsystem exist" switch. The existing operator-facing {@code
 * "features"."workspaceQuotas"} setting is unchanged and still decides whether an active subsystem
 * actually enforces its limits.
 *
 * <p>Setting {@code flow.quotas.enabled=true} in {@code engine} mode is unsupported - there is no
 * {@link WorkspaceService} bean to read quotas from and quota-checking callers will fail.
 */
public final class FlowQuotaProperties {

  static final String UNIFIED_PROPERTY = "flow.quotas.enabled";

  private FlowQuotaProperties() {}

  /** Whether workspace quotas (limits and the per-workspace run-duration ceiling) are available. */
  public static boolean isQuotasEnabled(Environment environment) {
    String configured = environment.getProperty(UNIFIED_PROPERTY);
    if (configured != null) {
      return Boolean.parseBoolean(configured);
    }
    return FlowMode.resolve(environment) == FlowMode.STANDALONE;
  }
}
