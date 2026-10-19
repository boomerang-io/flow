package io.boomerang.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A definition read shows a secret that is only a Flow reference as written and masks everything
 * else; a run read masks every secret, because a run's params hold resolved values.
 */
class DataAdapterUtilTest {

  private static final String REFERENCE = "$(workflow.params.aiToken)";

  @Test
  void aDefinitionReadShowsALoneReferenceAndMasksTheRest() {
    List<RunParam> read =
        DataAdapterUtil.filterDefinitionValueByFieldType(values(), ParamType.secret);

    assertEquals(REFERENCE, value(read, "token"));
    assertEquals(DataAdapterUtil.REDACTED, value(read, "literal"));
    assertEquals(DataAdapterUtil.REDACTED, value(read, "bearer"));
    assertEquals(DataAdapterUtil.REDACTED, value(read, "shell"));
    assertEquals("eu-west-1", value(read, "region"));
  }

  @Test
  void aRunReadMasksEverySecretIncludingALoneReference() {
    List<RunParam> read = DataAdapterUtil.filterRunParamValueByFieldType(values(), ParamType.secret);

    assertEquals(DataAdapterUtil.REDACTED, value(read, "token"));
    assertEquals(DataAdapterUtil.REDACTED, value(read, "literal"));
  }

  @Test
  void aPasswordDefaultThatIsOnlyAReferenceIsShownOnEveryDefinitionForm() {
    List<AbstractParam> copied =
        DataAdapterUtil.filterParamSpecValueByFieldType(
            specs(), "password", DataAdapterUtil.REDACTED);
    assertEquals(REFERENCE, copied.get(0).getDefaultValue());
    assertEquals(DataAdapterUtil.REDACTED, copied.get(1).getDefaultValue());

    List<AbstractParam> blanked = specs();
    DataAdapterUtil.filterParamSpecValueByFieldType(blanked, "password");
    assertEquals(REFERENCE, blanked.get(0).getDefaultValue());
    assertEquals("", blanked.get(1).getDefaultValue());

    List<AbstractParam> nulled = specs();
    DataAdapterUtil.filterValueByFieldType(nulled, true, "password");
    assertEquals(REFERENCE, nulled.get(0).getDefaultValue());
    assertNull(nulled.get(0).getHiddenValue(), "a shown reference is not flagged hidden");
    assertNull(nulled.get(1).getDefaultValue());
    assertEquals(Boolean.TRUE, nulled.get(1).getHiddenValue());
  }

  private static List<RunParam> values() {
    return new LinkedList<>(
        List.of(
            new RunParam("token", REFERENCE, ParamType.secret),
            new RunParam("literal", "s3cr3t-value", ParamType.secret),
            new RunParam("bearer", "Bearer $(params.token)", ParamType.secret),
            new RunParam("shell", "$(date)", ParamType.secret),
            new RunParam("region", "eu-west-1", ParamType.string)));
  }

  private static List<AbstractParam> specs() {
    AbstractParam reference = new AbstractParam();
    reference.setName("token");
    reference.setType("password");
    reference.setDefaultValue(REFERENCE);
    AbstractParam literal = new AbstractParam();
    literal.setName("other");
    literal.setType("password");
    literal.setDefaultValue("s3cr3t-value");
    return new LinkedList<>(List.of(reference, literal));
  }

  private static Object value(List<RunParam> params, String name) {
    return params.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow().getValue();
  }
}
