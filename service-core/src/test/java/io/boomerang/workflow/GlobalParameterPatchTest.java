package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A global param is updated by a JSON Merge Patch: an absent member is unchanged, a null member is
 * cleared, any other member replaces the stored one, and a secured value follows the one secret
 * rule for the redaction marker.
 */
class GlobalParameterPatchTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private ParameterService parameterService;

  @Test
  void anAbsentMemberIsUnchangedANullClearsItAndAValueReplacesIt() {
    String name = create("text", "first");

    parameterService.patch(name, json("{\"label\":\"Region\"}"));
    AbstractParam stored = unfiltered(name);
    assertThat(stored.getLabel()).isEqualTo("Region");
    assertThat(stored.getValue()).isEqualTo("first");
    assertThat(stored.getDescription()).isEqualTo("described");

    parameterService.patch(name, json("{\"description\":null,\"value\":\"second\"}"));
    stored = unfiltered(name);
    assertThat(stored.getDescription()).isNull();
    assertThat(stored.getValue()).isEqualTo("second");
    assertThat(stored.getLabel()).isEqualTo("Region");
  }

  @Test
  void anUnknownMemberIsIgnoredAndTheReadOnlyMarkerIsNeverStored() {
    String name = create("text", "first");

    parameterService.patch(name, json("{\"nope\":1,\"hiddenValue\":true,\"value\":\"kept\"}"));

    AbstractParam stored = unfiltered(name);
    assertThat(stored.getValue()).isEqualTo("kept");
    assertThat(stored.getHiddenValue()).isNull();
  }

  @Test
  void aSecuredValueKeepsOnTheMarkerAndClearsOnABlankOrANull() {
    String name = create("password", "s3cr3t-0001");

    AbstractParam response = parameterService.patch(name, json("{\"value\":\"*****\"}"));
    assertThat(response.getValue()).isEqualTo(DataAdapterUtil.REDACTED);
    assertThat(unfiltered(name).getValue()).isEqualTo("s3cr3t-0001");

    parameterService.patch(name, json("{\"description\":\"edited\"}"));
    assertThat(unfiltered(name).getValue()).isEqualTo("s3cr3t-0001");

    parameterService.patch(name, json("{\"value\":\"\"}"));
    assertThat(unfiltered(name).getValue()).isEqualTo("");

    parameterService.patch(name, json("{\"value\":\"s3cr3t-0002\"}"));
    assertThat(unfiltered(name).getValue()).isEqualTo("s3cr3t-0002");

    parameterService.patch(name, json("{\"value\":null}"));
    assertThat(unfiltered(name).getValue()).isNull();
  }

  @Test
  void aRenameAnUnknownNameAndABodyThatIsNotAnObjectAreRefused() {
    String name = create("text", "first");

    assertThat(refused(() -> parameterService.patch(name, json("{\"name\":\"other\"}"))))
        .isEqualTo(BoomerangError.PARAMS_INVALID_REQ.getCode());
    assertThat(refused(() -> parameterService.patch(name, json("[]"))))
        .isEqualTo(BoomerangError.PARAMS_INVALID_REQ.getCode());
    assertThat(refused(() -> parameterService.patch("no-such-param-" + UUID.randomUUID(), json("{}"))))
        .isEqualTo(BoomerangError.PARAMS_INVALID_REFERENCE.getCode());

    // The name in the body may repeat the path.
    parameterService.patch(name, json("{\"name\":\"" + name + "\",\"value\":\"same\"}"));
    assertThat(unfiltered(name).getValue()).isEqualTo("same");
  }

  private String create(String type, String value) {
    AbstractParam param = new AbstractParam();
    param.setName("patch" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    param.setType(type);
    param.setValue(value);
    param.setDescription("described");
    parameterService.create(param);
    return param.getName();
  }

  private AbstractParam unfiltered(String name) {
    return parameterService.getAllUnfiltered().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static int refused(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(BoomerangException.class, call).getCode();
  }

  private static JsonNode json(String body) {
    return MAPPER.readTree(body);
  }
}
