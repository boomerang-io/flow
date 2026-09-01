package io.boomerang.common.validation;

/**
 * Slug rule for Task / WorkflowTemplate names: alphanumeric and hyphen, case-insensitive.
 *
 * <p>The single source of truth for the regex - replaces {@code TaskService.java:90}'s {@code
 * "^([0-9a-zA-Z\\-]+)$"} and {@code WorkflowTemplateService.java:45}'s double-escaped {@code
 * "^([0-9a-zA-Z\\\\-]+)$"}, which admitted a literal backslash.
 */
public final class ResourceName {

  public static final String REGEX = "^[0-9a-zA-Z-]+$";

  private ResourceName() {}
}
