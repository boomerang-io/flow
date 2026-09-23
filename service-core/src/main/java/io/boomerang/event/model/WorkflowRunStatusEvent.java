package io.boomerang.event.model;

import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.PojoCloudEventData;
import java.io.IOException;
import java.time.ZoneOffset;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class WorkflowRunStatusEvent extends Event {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  // The projected payload: a RunStatusSummary, or the full WorkflowRun when the sink asks for it.
  private Object data;

  @Override
  public CloudEvent toCloudEvent() throws IOException {

    CloudEventData eventData = PojoCloudEventData.wrap(this.data, MAPPER::writeValueAsBytes);

    // @formatter:off
    CloudEventBuilder cloudEventBuilder =
        CloudEventBuilder.v1()
            .withId(getId())
            .withSource(getSource())
            .withSubject(getSubject())
            .withType(getType().getCloudEventType())
            .withTime(getDate().toInstant().atOffset(ZoneOffset.UTC))
            .withData(MediaType.APPLICATION_JSON_VALUE, eventData);
    // @formatter:on

    return cloudEventBuilder.build();
  }

  public void setData(Object data) {
    this.data = data;
  }
}
