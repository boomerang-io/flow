package io.boomerang.event.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.event.enums.EventType;
import io.cloudevents.CloudEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A15.2: {@link GenericStatusEvent} built its CloudEvent JSON payload with Gson's {@code
 * JsonObject} while every other event in this package (and the rest of the codebase) is on
 * Jackson. This pins the serialised payload shape across the Gson -> Jackson 3 {@code ObjectNode}
 * rewrite - same keys, same values, same compact (no-whitespace) JSON object shape.
 */
class GenericStatusEventTest {

  @Test
  void serialisesAdditionalDataAsACompactJsonObject() throws Exception {
    GenericStatusEvent event = new GenericStatusEvent();
    event.setId("evt-1");
    event.setSource(URI.create("urn:boomerang:test"));
    event.setSubject("wfe-123");
    event.setDate(new Date(0));
    event.setType(EventType.TRIGGER);

    Map<String, String> additionalData = new LinkedHashMap<>();
    additionalData.put("workflowRef", "my-workflow");
    additionalData.put("status", "succeeded");
    additionalData.put("message", "value with \"quotes\" and a , comma");
    event.setAdditionalData(additionalData);

    CloudEvent cloudEvent = event.toCloudEvent();

    String payload = new String(cloudEvent.getData().toBytes(), StandardCharsets.UTF_8);

    assertThat(payload)
        .isEqualTo(
            "{\"workflowRef\":\"my-workflow\",\"status\":\"succeeded\","
                + "\"message\":\"value with \\\"quotes\\\" and a , comma\"}");
  }

  @Test
  void emptyAdditionalDataProducesAnEmptyJsonObject() throws Exception {
    GenericStatusEvent event = new GenericStatusEvent();
    event.setId("evt-2");
    event.setSource(URI.create("urn:boomerang:test"));
    event.setSubject("wfe-124");
    event.setDate(new Date(0));
    event.setType(EventType.TRIGGER);

    CloudEvent cloudEvent = event.toCloudEvent();

    String payload = new String(cloudEvent.getData().toBytes(), StandardCharsets.UTF_8);
    assertThat(payload).isEqualTo("{}");
  }
}
