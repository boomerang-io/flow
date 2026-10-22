package io.boomerang.common.validation;

import io.boomerang.common.util.ParameterUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Null is valid here - a required param name is a separate {@code @NotNull} concern. */
public final class ParamNameValidator implements ConstraintValidator<ParamName, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || ParameterUtil.isValidParamName(value);
  }
}
