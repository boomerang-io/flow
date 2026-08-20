package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import org.junit.jupiter.api.Test;

/**
 * Redaction must key off the param's own type. The single caller
 * (ParameterService.convertToAbstractParamAndFilter) passes "password", so redacting regardless of
 * type blanks the value of every global parameter the API returns, not just the secrets.
 */
class ParamRedactionTest {

  private static AbstractParam param(String type, Object value) {
    AbstractParam param = new AbstractParam();
    param.setType(type);
    param.setValue(value);
    param.setDefaultValue(value);
    return param;
  }

  @Test
  void aPasswordParamIsRedacted() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("password", "s3cret"), false, "password");

    assertNull(result.getValue());
    assertTrue(result.getHiddenValue());
  }

  @Test
  void aNonPasswordParamKeepsItsValue() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("text", "not-a-secret"), false, "password");

    assertEquals("not-a-secret", result.getValue(), "a text param must not be redacted");
    assertNull(result.getHiddenValue(), "a text param must not be marked hidden");
  }

  @Test
  void theDefaultValueVariantRedactsOnlyTheDefault() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("password", "s3cret"), true, "password");

    assertNull(result.getDefaultValue());
    assertEquals("s3cret", result.getValue(), "only the default value is cleared in this mode");
  }

  @Test
  void aNullParamIsReturnedRatherThanThrowing() {
    assertNull(DataAdapterUtil.filterAbstractParam(null, false, "password"));
  }
}
