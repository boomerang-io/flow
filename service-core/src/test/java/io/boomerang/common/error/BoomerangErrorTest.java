package io.boomerang.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Error codes are frozen from 5.0.0: a client may key on the code, so each names exactly one
 * reason, and the reasons that once shared a code keep the code they first had.
 */
class BoomerangErrorTest {

  @Test
  void everyCodeNamesOneReason() {
    Map<Integer, List<String>> shared =
        Arrays.stream(BoomerangError.values())
            .collect(
                Collectors.groupingBy(
                    BoomerangError::getCode,
                    Collectors.mapping(BoomerangError::getReason, Collectors.toList())));
    shared.values().removeIf(reasons -> reasons.size() == 1);

    assertThat(shared).isEmpty();
  }

  @Test
  void everyReasonIsNamedOnce() {
    assertThat(Arrays.stream(BoomerangError.values()).map(BoomerangError::getReason))
        .doesNotHaveDuplicates();
  }

  @Test
  void theReasonsThatSharedACodeKeepTheFirstAndTheOthersHaveTheirOwn() {
    assertThat(BoomerangError.USER_UNABLE_TO_DELETE.getCode()).isEqualTo(101);
    assertThat(BoomerangError.USER_NOT_FOUND.getCode()).isEqualTo(102);
    assertThat(BoomerangError.TOO_MANY_REQUESTS.getCode()).isEqualTo(429);
    assertThat(BoomerangError.INTERNAL_SERVER_ERROR.getCode()).isEqualTo(500);
    assertThat(BoomerangError.DATA_CONVERSION_FAILED.getCode()).isEqualTo(1040);
    assertThat(BoomerangError.TASK_INVALID_WORKFLOWREF.getCode()).isEqualTo(1405);
    assertThat(BoomerangError.TASK_DELETE_IN_USE.getCode()).isEqualTo(1407);
    assertThat(BoomerangError.PARAMS_NON_UNIQUE_REFERENCE.getCode()).isEqualTo(1802);
    assertThat(BoomerangError.PARAMS_NON_UNIQUE_KEY.getCode()).isEqualTo(1805);
  }
}
