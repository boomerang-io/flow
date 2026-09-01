package io.boomerang.common.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ResourceName#REGEX} against the bug it replaces: {@code
 * WorkflowTemplateService.java:45}'s double-escaped {@code "^([0-9a-zA-Z\\\\-]+)$"} admitted a
 * literal backslash, which {@code TaskService.java:90}'s regex correctly rejected.
 */
class ResourceNameTest {

  @Test
  void aBackslashDoesNotMatch() {
    assertFalse("a\\b".matches(ResourceName.REGEX));
  }

  @Test
  void blankDoesNotMatch() {
    assertFalse("".matches(ResourceName.REGEX));
  }

  @Test
  void aSpaceDoesNotMatch() {
    assertFalse("a b".matches(ResourceName.REGEX));
  }

  @Test
  void alphanumericAndHyphenMatches() {
    assertTrue("My-Task-1".matches(ResourceName.REGEX));
  }
}
