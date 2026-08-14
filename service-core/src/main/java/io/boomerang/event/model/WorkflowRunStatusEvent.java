package io.boomerang.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.boomerang.common.model.WorkflowRun;
import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.PojoCloudEventData;
import java.io.IOException;
import java.time.ZoneOffset;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;

public class WorkflowRunStatusEvent extends Event {
  private static final Logger LOGGER = LogManager.getLogger();

  private WorkflowRun workflowRun;

  @Override
  public CloudEvent toCloudEvent() throws IOException {

    ObjectMapper mapper = new ObjectMapper();
    //    JsonNode node = mapper.convertValue(workflowRunEntity, JsonNode.class);
    CloudEventData data = PojoCloudEventData.wrap(this.workflowRun, mapper::writeValueAsBytes);
    LOGGER.info("Data: " + new String(data.toBytes()));
    // @formatter:off
    CloudEventBuilder cloudEventBuilder =
        CloudEventBuilder.v1()
            .withId(getId())
            .withSource(getSource())
            .withSubject(getSubject())
            .withType(getType().getCloudEventType())
            .withTime(getDate().toInstant().atOffset(ZoneOffset.UTC))
            //        .withData(MediaType.APPLICATION_JSON_VALUE, node.toString().getBytes());
            .withData(MediaType.APPLICATION_JSON_VALUE, data);
    // @formatter:on

    return cloudEventBuilder.build();
  }

  public void setWorkflowRun(WorkflowRun workflowRun) {
    this.workflowRun = workflowRun;
  }
}
