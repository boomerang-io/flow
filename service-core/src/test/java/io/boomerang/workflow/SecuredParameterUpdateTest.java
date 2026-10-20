package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * A secured parameter's value is never returned by a read, so a patch that edits another field
 * and leaves the value out keeps the stored secret.
 */
class SecuredParameterUpdateTest extends AbstractEngineIntegrationTest {

  @Autowired private ParameterService parameterService;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void editingAnotherFieldOnASecuredParameterPreservesItsStoredValue() {
    AbstractParam created = new AbstractParam();
    created.setName("secured-update-test-param");
    created.setType("password");
    created.setValue("hunter2");
    created.setDescription("original");
    parameterService.create(created);

    AbstractParam updated =
        parameterService.patch(
            "secured-update-test-param", objectMapper.readTree("{\"description\":\"edited\"}"));

    assertEquals("edited", updated.getDescription(), "the field actually being edited must change");
    assertEquals(DataAdapterUtil.REDACTED, updated.getValue(), "the response itself stays masked");

    AbstractParam stored = unfiltered("secured-update-test-param");
    assertEquals("hunter2", stored.getValue(), "the stored secret must survive the edit");
    assertEquals("edited", stored.getDescription());
  }

  @Test
  void anExplicitNullClearsAnUnsecuredValue() {
    AbstractParam created = new AbstractParam();
    created.setName("plain-update-test-param");
    created.setType("text");
    created.setValue("hello");
    parameterService.create(created);

    parameterService.patch("plain-update-test-param", objectMapper.readTree("{\"value\":null}"));

    assertNull(unfiltered("plain-update-test-param").getValue());
  }

  private AbstractParam unfiltered(String name) {
    List<AbstractParam> all = parameterService.getAllUnfiltered();
    return all.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
  }
}
