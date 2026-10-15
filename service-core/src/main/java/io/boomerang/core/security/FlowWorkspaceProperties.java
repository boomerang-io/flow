package io.boomerang.core.security;

import org.springframework.core.env.Environment;

/**
 * The single {@code flow.workspace.single} property (default {@code false}). When {@code true} the
 * instance serves the seeded {@code system} workspace alone: {@link SingleWorkspaceInterceptor}
 * refuses every other {@code {workspace}} path segment, and {@code WorkspaceService} refuses to
 * create or delete a workspace. An embedding product sets it together with {@code
 * flow.security.enabled=false}.
 */
public final class FlowWorkspaceProperties {

  static final String SINGLE_PROPERTY = "flow.workspace.single";

  private FlowWorkspaceProperties() {}

  /** Whether the instance serves only the {@code system} workspace. */
  public static boolean isSingle(Environment environment) {
    return Boolean.parseBoolean(environment.getProperty(SINGLE_PROPERTY));
  }
}
