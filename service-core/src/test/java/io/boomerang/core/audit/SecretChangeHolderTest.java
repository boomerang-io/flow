package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.util.DataAdapterUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The rules that decide which secret names one write changed or cleared. */
class SecretChangeHolderTest {

  @AfterEach
  void closeAnyOpenCollection() {
    SecretChangeHolder.close();
  }

  @Test
  void aNewOrDifferentValueIsChangedAndAKeptOneIsNot() {
    SecretChangeHolder.open();
    SecretChangeHolder.compare(
        Map.of("kept", "same", "rotated", "old"),
        Map.of("kept", "same", "rotated", "new", "added", "first"));

    assertThat(SecretChangeHolder.close())
        .isEqualTo(Map.of(SecretChangeHolder.CHANGED, List.of("added", "rotated")));
  }

  @Test
  void aSetValueThatEndsUnsetOrGoneIsCleared() {
    SecretChangeHolder.open();
    SecretChangeHolder.compare("blanked", "secret", "");
    SecretChangeHolder.compare(Map.of("removed", "secret"), Map.of());

    assertThat(SecretChangeHolder.close())
        .isEqualTo(Map.of(SecretChangeHolder.CLEARED, List.of("blanked", "removed")));
  }

  @Test
  void theRedactionMarkerAndABlankAreNoValue() {
    SecretChangeHolder.open();
    SecretChangeHolder.compare("legacy", DataAdapterUtil.REDACTED, null);
    SecretChangeHolder.compare("empty", "", "  ");

    assertThat(SecretChangeHolder.close()).isNull();
  }

  @Test
  void aParamsValueAndDefaultAreOneSecretAndOnlyItsTypeCounts() {
    AbstractParam before = param("token", "password", "v1", "d1");
    AbstractParam after = param("token", "password", "v1", "d2");
    AbstractParam plain = param("region", "text", "eu", null);

    SecretChangeHolder.open();
    SecretChangeHolder.compare(
        SecretChangeHolder.secretValues("params.", List.of(before, plain), "password"),
        SecretChangeHolder.secretValues("params.", List.of(after, plain), "password"));
    SecretChangeHolder.compare(
        SecretChangeHolder.secretRunValues(
            "tasks.work.", List.of(new RunParam("key", "a", ParamType.secret)), ParamType.secret),
        SecretChangeHolder.secretRunValues(
            "tasks.work.", List.of(new RunParam("key", "b", ParamType.string)), ParamType.secret));

    assertThat(SecretChangeHolder.close())
        .isEqualTo(
            Map.of(
                SecretChangeHolder.CHANGED, List.of("params.token"),
                SecretChangeHolder.CLEARED, List.of("tasks.work.key")));
  }

  @Test
  void outsideAnAuditedCallNothingIsRecorded() {
    SecretChangeHolder.compare("token", null, "secret");

    assertThat(SecretChangeHolder.open()).isTrue();
    assertThat(SecretChangeHolder.open()).as("an inner audited call joins the outer one").isFalse();
    assertThat(SecretChangeHolder.close()).isNull();
  }

  private static AbstractParam param(String name, String type, Object value, Object defaultValue) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setValue(value);
    param.setDefaultValue(defaultValue);
    return param;
  }
}
