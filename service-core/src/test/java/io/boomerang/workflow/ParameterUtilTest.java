package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
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
  void paramNameValidityMatchesTheWebappRule() {
    assertTrue(ParameterUtil.isValidParamName("githubToken"));
    assertTrue(ParameterUtil.isValidParamName("my-param_2"));
    assertTrue(ParameterUtil.isValidParamName("_private"));
    // '.' is the reference path separator; digits can't lead; no spaces.
    assertTrue(!ParameterUtil.isValidParamName("my.param"));
    assertTrue(!ParameterUtil.isValidParamName("2fast"));
    assertTrue(!ParameterUtil.isValidParamName("has space"));
    assertTrue(!ParameterUtil.isValidParamName(null));
    // Reserved: folds to PARAM_NAMES, the manifest env var.
    assertTrue(!ParameterUtil.isValidParamName("names"));
    assertTrue(!ParameterUtil.isValidParamName("Names"));
  }

  @Test
  void paramNameCollisionsIsEmptyForDistinctNames() {
    assertTrue(ParameterUtil.paramNameCollisions(List.of("alpha", "beta-name", "gamma")).isEmpty());
  }

  // A secret is a parameter type derived from the password field; a Tekton export keeps string.
  @Test
  void aPasswordFieldBecomesASecretRunParam() {
    AbstractParam password = new AbstractParam();
    password.setName("dbPassword");
    password.setType("password");
    AbstractParam text = new AbstractParam();
    text.setName("host");
    text.setType("text");

    List<RunParam> params = ParameterUtil.abstractParamToRunParam(List.of(password, text));

    assertEquals(ParamType.secret, params.get(0).getType());
    assertEquals(ParamType.string, params.get(1).getType());
    assertEquals(ParamType.string, ParameterUtil.getTektonParamType("password"));
  }

  // A value sent as a secret raises the declared type; nothing lowers a declared secret.
  @Test
  void addUniqueParamRaisesToSecretButNeverLowers() {
    List<RunParam> declared =
        new ArrayList<>(
            List.of(
                new RunParam("note", "default", ParamType.string),
                new RunParam("token", "default", ParamType.secret)));

    ParameterUtil.addUniqueParam(declared, new RunParam("note", "private", ParamType.secret));
    ParameterUtil.addUniqueParam(declared, new RunParam("token", "override", ParamType.string));
    ParameterUtil.addUniqueParam(declared, new RunParam("token", "untyped"));

    assertEquals(ParamType.secret, declared.get(0).getType());
    assertEquals(ParamType.secret, declared.get(1).getType());
    assertEquals("untyped", declared.get(1).getValue());
  }
}
