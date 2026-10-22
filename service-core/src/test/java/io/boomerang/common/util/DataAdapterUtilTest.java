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

    List<AbstractParam> inPlace = specs();
    DataAdapterUtil.filterParamSpecValueByFieldType(inPlace, "password");
    assertEquals(REFERENCE, inPlace.get(0).getDefaultValue());
    assertEquals(DataAdapterUtil.REDACTED, inPlace.get(1).getDefaultValue());

    List<AbstractParam> flagged = specs();
    DataAdapterUtil.filterValueByFieldType(flagged, true, "password");
    assertEquals(REFERENCE, flagged.get(0).getDefaultValue());
    assertNull(flagged.get(0).getHiddenValue(), "a shown reference is not flagged hidden");
    assertEquals(DataAdapterUtil.REDACTED, flagged.get(1).getDefaultValue());
    assertEquals(Boolean.TRUE, flagged.get(1).getHiddenValue());
  }

  @Test
  void aSetSecretReadsAsTheMarkerAndAnUnsetOneAsNothingOnEveryForm() {
    assertEquals(DataAdapterUtil.REDACTED, DataAdapterUtil.maskSecretValue("s3cr3t-value"));
    assertNull(DataAdapterUtil.maskSecretValue(null));
    assertNull(DataAdapterUtil.maskSecretValue(""));
    assertEquals(REFERENCE, DataAdapterUtil.maskSecretValue(REFERENCE));

    List<RunParam> unset =
        new LinkedList<>(List.of(new RunParam("token", "", ParamType.secret)));
    assertNull(value(DataAdapterUtil.filterRunParamValueByFieldType(unset, ParamType.secret), "token"));
    assertNull(
        value(DataAdapterUtil.filterDefinitionValueByFieldType(unset, ParamType.secret), "token"));

    AbstractParam noDefault = new AbstractParam();
    noDefault.setName("token");
    noDefault.setType("password");
    List<AbstractParam> specs = new LinkedList<>(List.of(noDefault));
    DataAdapterUtil.filterParamSpecValueByFieldType(specs, "password");
    assertNull(specs.get(0).getDefaultValue());
  }

  @Test
  void theMarkerOrNoValueKeepsTheStoredSecretABlankClearsItAndAnythingElseReplacesIt() {
    assertEquals("stored", DataAdapterUtil.keepSecretValue(DataAdapterUtil.REDACTED, "stored"));
    assertEquals("stored", DataAdapterUtil.keepSecretValue(null, "stored"));
    assertEquals("", DataAdapterUtil.keepSecretValue("", "stored"));
    assertEquals("new", DataAdapterUtil.keepSecretValue("new", "stored"));
    assertNull(
        DataAdapterUtil.keepSecretValue(DataAdapterUtil.REDACTED, null),
        "a marker with nothing stored is no value, never the secret");
    assertNull(
        DataAdapterUtil.keepSecretValue(DataAdapterUtil.REDACTED, DataAdapterUtil.REDACTED),
        "a marker an earlier release stored is no value either");

    List<RunParam> incoming =
        new LinkedList<>(
            List.of(
                new RunParam("Token", DataAdapterUtil.REDACTED, ParamType.secret),
                new RunParam("cleared", "", ParamType.secret),
                new RunParam("fresh", DataAdapterUtil.REDACTED, ParamType.secret)));
    DataAdapterUtil.keepRunParamValueByFieldType(
        incoming,
        List.of(
            new RunParam("token", "stored-token", ParamType.secret),
            new RunParam("cleared", "stored-cleared", ParamType.secret)),
        ParamType.secret);
    assertEquals("stored-token", value(incoming, "Token"), "matched by name in any case");
    assertEquals("", value(incoming, "cleared"));
    assertNull(value(incoming, "fresh"));
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
