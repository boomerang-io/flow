package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every reason the SDK names is one the engine knows, so a dispatcher using these constants never
 * ends a task with a reason the engine reads as an ordinary failure. The engine's set is read from
 * its source, because the SDK and service-core never depend on each other.
 */
class StatusReasonTest {

  // Surefire runs each module from its own directory; service-core is a sibling module.
  private static final Path ENGINE_REASONS =
      Path.of(
          "..", "service-core", "src", "main", "java", "io", "boomerang", "common", "model",
          "StatusReason.java");

  private static final Pattern CONSTANT =
      Pattern.compile("public static final String [A-Z_]+ = \"(\\w+)\";");

  @Test
  void everyReasonTheSdkNamesIsOneTheEngineKnows() throws Exception {
    Matcher matcher = CONSTANT.matcher(Files.readString(ENGINE_REASONS));
    Set<String> engineReasons = new HashSet<>();
    while (matcher.find()) {
      engineReasons.add(matcher.group(1));
    }

    assertThat(engineReasons).isNotEmpty().containsAll(sdkReasons());
  }

  @Test
  void taskFailuresConstantsAreTheSameReasons() {
    assertThat(TaskFailure.DISPATCH_ERROR).isEqualTo(StatusReason.DISPATCH_ERROR);
    assertThat(TaskFailure.EXCEEDED_QUOTA).isEqualTo(StatusReason.EXCEEDED_QUOTA);
    assertThat(TaskFailure.START_TIMEOUT).isEqualTo(StatusReason.START_TIMEOUT);
    assertThat(TaskFailure.RATE_LIMITED).isEqualTo(StatusReason.RATE_LIMITED);
  }

  private static Set<String> sdkReasons() {
    return Arrays.stream(StatusReason.class.getFields())
        .filter(field -> Modifier.isStatic(field.getModifiers()))
        .filter(field -> field.getType() == String.class)
        .map(StatusReasonTest::value)
        .collect(Collectors.toSet());
  }

  private static String value(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
