package io.boomerang.integrations.enums;

/**
 * How a workspace links an integration: {@code app}, Flow installs it and fills the credential it
 * shares; {@code form}, the workspace enters every field of the template itself.
 */
public enum IntegrationKind {
  app,
  form // NOSONAR
}
