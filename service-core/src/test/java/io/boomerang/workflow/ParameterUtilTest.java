package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.model.RunParam;
import io.boomerang.common.util.ParameterUtil;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The case-insensitive matching ruling (2026-08-26) at the merge and fold level. */
class ParameterUtilTest {

  @Test
  void addUniqueParamOverridesCaseVariantAndKeepsDeclaredCasing() {
    List<RunParam> declared = new ArrayList<>(List.of(new RunParam("githubToken", "default")));

    ParameterUtil.addUniqueParam(declared, new RunParam("GITHUBTOKEN", "override"));

    assertEquals(1, declared.size(), "a case variant must override, not duplicate");
    assertEquals("githubToken", declared.get(0).getName(), "declared casing is the display form");
    assertEquals("override", declared.get(0).getValue());
  }

  @Test
  void envFoldUppercasesAndReplacesSeparators() {
    assertEquals("MY_PARAM", ParameterUtil.envFold("my-param"));
    assertEquals("MY_PARAM", ParameterUtil.envFold("my_param"));
    assertEquals("GITHUBTOKEN", ParameterUtil.envFold("githubToken"));
  }

  @Test
  void paramNameCollisionsGroupsCaseAndSeparatorVariants() {
    List<List<String>> collisions =
        ParameterUtil.paramNameCollisions(
            List.of("my-param", "my_param", "distinct", "Token", "token"));

    assertEquals(2, collisions.size());
    assertTrue(collisions.contains(List.of("my-param", "my_param")));
    assertTrue(collisions.contains(List.of("Token", "token")));
  }

  @Test
  void paramNameCollisionsIsEmptyForDistinctNames() {
    assertTrue(ParameterUtil.paramNameCollisions(List.of("alpha", "beta-name", "gamma")).isEmpty());
  }
}
