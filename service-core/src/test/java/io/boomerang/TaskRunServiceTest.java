package io.boomerang;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/*
 * Placeholder for a v4-era unit test that no longer compiled (no package declaration, missing
 * imports, and a TaskRun constructor / TaskRunRepository finder that no longer exist), breaking
 * test-compile for the whole module. Rebuilding a TaskRunService query unit test is optional
 * follow-up; the io.boomerang.engine integration tests cover the lifecycle paths.
 */
@Disabled(
    "Legacy v4-era test referenced APIs that no longer exist and did not compile. Superseded by"
        + " the io.boomerang.engine integration tests.")
class TaskRunServiceTest {

  @Test
  void legacyTestRemoved() {
    // Intentionally empty - see class comment.
  }
}
