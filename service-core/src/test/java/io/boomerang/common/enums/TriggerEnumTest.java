package io.boomerang.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.model.WorkflowSubmitRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A submit body's trigger is read ignoring case, and written in lower case. */
class TriggerEnumTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void aBodyTriggerIsReadIgnoringCase() {
    for (String sent : new String[] {"manual", "Manual", "MANUAL"}) {
      WorkflowSubmitRequest request =
          MAPPER.readValue("{\"trigger\":\"" + sent + "\"}", WorkflowSubmitRequest.class);
      assertThat(request.getTrigger()).isEqualTo(TriggerEnum.manual);
    }
    assertThat(TriggerEnum.getFlowTriggerEnum("Api")).isEqualTo(TriggerEnum.api);
  }

  @Test
  void anUnknownTriggerIsRefused() {
    assertThatThrownBy(
            () -> MAPPER.readValue("{\"trigger\":\"nightly\"}", WorkflowSubmitRequest.class))
        .hasMessageContaining("nightly");
  }

  @Test
  void aTriggerIsWrittenInLowerCase() {
    assertThat(MAPPER.writeValueAsString(TriggerEnum.manual)).isEqualTo("\"manual\"");
  }
}
