package io.boomerang.common.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Delegates to {@link io.boomerang.common.util.ParameterUtil#isValidParamName(String)} - not a
 * plain regex, since the rule also rejects the reserved {@code NAMES} name (folds to {@code
 * PARAM_NAMES}, the env var carrying the param-name manifest itself).
 */
@Documented
@Constraint(validatedBy = ParamNameValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ParamName {

  String message() default "invalid parameter name";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
