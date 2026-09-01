package io.boomerang.common.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.boomerang.common.error.BoomerangError;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Slug rule for Task / WorkflowTemplate names: alphanumeric and hyphen, case-insensitive.
 *
 * <p>The single source of truth for the regex - replaces {@code TaskService.java:90}'s {@code
 * "^([0-9a-zA-Z\\-]+)$"} and {@code WorkflowTemplateService.java:45}'s double-escaped {@code
 * "^([0-9a-zA-Z\\\\-]+)$"}, which admitted a literal backslash.
 */
@Documented
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@NotBlank
@Pattern(regexp = ResourceName.REGEX)
@ReportAsSingleViolation
public @interface ResourceName {
  String REGEX = "^[0-9a-zA-Z-]+$";

  /** Which platform error the violation maps to - read by RestExceptionHandler. */
  BoomerangError error() default BoomerangError.TASK_INVALID_NAME;

  String message() default "invalid resource name";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
