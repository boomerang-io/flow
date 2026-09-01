package io.boomerang.event.model;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.Map;
import org.apache.logging.log4j.util.Strings;
import org.springframework.http.MediaType;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public class GenericStatusEvent extends Event {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private String initiatorContext;
  private Map<String, String> additionalData;

  @Override
  public CloudEvent toCloudEvent() throws IOException {
    ObjectNode jsonData = MAPPER.createObjectNode();

    // Add additional data to JSON data
    if (additionalData != null && !additionalData.isEmpty()) {
      additionalData.forEach(jsonData::put);
    }

    // @formatter:off
    CloudEventBuilder cloudEventBuilder =
        CloudEventBuilder.v1()
            .withId(getId())
            .withSource(getSource())
            .withSubject(getSubject())
            .withType(getType().getCloudEventType())
            .withTime(getDate().toInstant().atOffset(ZoneOffset.UTC))
            .withData(MediaType.APPLICATION_JSON.toString(), jsonData.toString().getBytes());
    // @formatter:on

    if (Strings.isNotEmpty(initiatorContext)) {
      cloudEventBuilder =
          cloudEventBuilder.withExtension(EXTENSION_ATTRIBUTE_CONTEXT, initiatorContext);
    }

    return cloudEventBuilder.build();
  }

  public Map<String, String> getAdditionalData() {
    return additionalData;
  }

  public void setAdditionalData(Map<String, String> additionalData) {
    this.additionalData = additionalData;
  }
}
