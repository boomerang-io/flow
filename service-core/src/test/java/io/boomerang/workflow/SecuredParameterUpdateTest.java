package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A secured parameter's value is never returned by a read (it is filtered to null) - an update
 * that carries that filtered null back must not let it overwrite the stored secret just because
 * the caller was editing some other field.
 */
class SecuredParameterUpdateTest extends AbstractEngineIntegrationTest {

  @Autowired private ParameterService parameterService;

  @Test
  void editingAnotherFieldOnASecuredParameterPreservesItsStoredValue() {
    AbstractParam created = new AbstractParam();
    created.setName("secured-update-test-param");
    created.setType("password");
    created.setValue("hunter2");
    created.setDescription("original");
    parameterService.create(created);

    // The read -> edit -> write round trip: the caller never saw the real value, so what they
    // send back for this field is the filtered null - only "description" is a real edit.
    AbstractParam edit = new AbstractParam();
    edit.setName("secured-update-test-param");
    edit.setType("password");
    edit.setValue(null);
    edit.setDescription("edited");

    AbstractParam updated = parameterService.update(edit);

    assertEquals("edited", updated.getDescription(), "the field actually being edited must change");
    assertNull(updated.getValue(), "the response itself stays filtered");

    AbstractParam stored = unfiltered("secured-update-test-param");
    assertEquals("hunter2", stored.getValue(), "the stored secret must survive the edit");
    assertEquals("edited", stored.getDescription());
  }

  @Test
  void editingAnotherFieldOnAnUnsecuredParameterStillClearsAnExplicitNullValue() {
    AbstractParam created = new AbstractParam();
    created.setName("plain-update-test-param");
    created.setType("text");
    created.setValue("hello");
    parameterService.create(created);

    AbstractParam edit = new AbstractParam();
    edit.setName("plain-update-test-param");
    edit.setType("text");
    edit.setValue(null);

    parameterService.update(edit);

    // Only a secured field gets the preserve-on-blank treatment - an unsecured field's explicit
    // null is a real request to clear the value.
    AbstractParam stored = unfiltered("plain-update-test-param");
    assertNull(stored.getValue());
  }

  private AbstractParam unfiltered(String name) {
    List<AbstractParam> all = parameterService.getAllUnfiltered();
    return all.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }
}
