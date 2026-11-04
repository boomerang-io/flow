package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ActivationCodeTest {

  private static final String CODE = "0123456789abcdef0123456789abcdef";

  @Test
  void aCodeOfAtLeast32CharactersMatchesOnlyItself() {
    ActivationCode code = new ActivationCode(CODE, "");

    assertThat(code.matches(CODE)).isTrue();
    assertThat(code.matches(CODE + "x")).isFalse();
    assertThat(code.matches("")).isFalse();
    assertThat(code.matches(null)).isFalse();
  }

  @Test
  void aShorterCodeStopsTheStart() {
    assertThatThrownBy(() -> new ActivationCode("test", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("flow.activation-code must be at least 32 characters.");
  }

  @Test
  void noCodeMeansActivationIsOff() {
    ActivationCode code = new ActivationCode("", "");

    assertThat(code.matches("")).isFalse();
    assertThat(code.matches(CODE)).isFalse();
  }

  @Test
  void theRetiredNameStillWorksWhenTheNewOneIsUnset() {
    assertThat(new ActivationCode("", CODE).matches(CODE)).isTrue();
  }

  @Test
  void theRetiredNameIsHeldToTheSameLength() {
    assertThatThrownBy(() -> new ActivationCode("", "test"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void bothNamesSetAndDifferentStopsTheStartButEqualIsFine() {
    assertThatThrownBy(() -> new ActivationCode(CODE, CODE.replace('0', '9')))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("both set and differ");
    assertThat(new ActivationCode(CODE, CODE).matches(CODE)).isTrue();
  }
}
