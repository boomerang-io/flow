package io.boomerang.event.model;

import io.boomerang.common.entity.WorkflowEntity;
import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.PojoCloudEventData;
import java.io.IOException;
import java.time.ZoneOffset;
import org.apache.logging.log4j.util.Strings;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class WorkflowStatusEvent extends Event {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private WorkflowEntity workflowEntity;

  @Override
  public CloudEvent toCloudEvent() throws IOException {

    CloudEventData data = PojoCloudEventData.wrap(workflowEntity, MAPPER::writeValueAsBytes);

    // @formatter:off
    CloudEventBuilder cloudEventBuilder =
        CloudEventBuilder.v1()
            .withId(getId())
            .withSource(getSource())
            .withSubject(getSubject())
            .withType(getType().getCloudEventType())
            .withTime(getDate().toInstant().atOffset(ZoneOffset.UTC))
            .withData(MediaType.APPLICATION_JSON.toString(), data);
    // @formatter:on

    if (Strings.isNotEmpty(super.getInitiatorContext())) {
      cloudEventBuilder =
          cloudEventBuilder.withExtension(EXTENSION_ATTRIBUTE_CONTEXT, super.getInitiatorContext());
    }

    return cloudEventBuilder.build();
  }

  public void setWorkflow(WorkflowEntity workflowEntity) {
    this.workflowEntity = workflowEntity;
  }
}
