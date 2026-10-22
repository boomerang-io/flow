package io.boomerang.misc;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.boomerang.tests.TestUtil;
import io.cloudevents.CloudEvent;
import io.cloudevents.json.Json;
import io.cloudevents.v1.AttributesImpl;
import io.cloudevents.v1.CloudEventBuilder;
import io.cloudevents.v1.CloudEventImpl;
import io.cloudevents.v1.http.Unmarshallers;
import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EventProcessorTest {

  @Test
  public void testCloudEventProcessing() throws IOException {
    // Build Cloud Event
    String payload = buildEvent();

    // Unmarshall Cloud Event
    Map<String, Object> headers = new HashMap<>();
    headers.put("Content-Type", "application/cloudevents+json");

    System.out.println("Process Message - Event as Message String: " + payload);

    CloudEvent<AttributesImpl, JsonNode> event =
        Unmarshallers.structured(JsonNode.class)
            .withHeaders(() -> headers)
            .withPayload(() -> payload)
            .unmarshal();

    System.out.println("Process Message - Attributes: " + event.getAttributes().toString());
    System.out.println("Process Message - Payload: " + event.getData().get().toString());

    JsonNode cloudEventData = event.getData().get();
    if (cloudEventData != null) {
      System.out.println("Process Message - data as JsonNode: " + cloudEventData);
    }
    ;

    processTrigger(cloudEventData, "1234", "dockerhub");
  }

  private String buildEvent() throws IOException {
    final String eventId = UUID.randomUUID().toString();
    final String eventType = "io.boomerang.eventing.test";
    final URI uri = URI.create("https://localhost/listener");
    String payloadAsString = TestUtil.getMockFile("json/event-dockerhub-payload.json");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode payload = mapper.readTree(payloadAsString);

    System.out.println("Payload: " + payload.toPrettyString());

    final CloudEventImpl<JsonNode> cloudEvent =
        CloudEventBuilder.<JsonNode>builder()
            .withType(eventType)
            .withId(eventId)
            .withSource(uri)
            .withData(payload)
            .withSubject("test")
            .withTime(ZonedDateTime.now())
            .build();

    final String jsonPayload = Json.encode(cloudEvent);
    System.out.println("CloudEvent: " + jsonPayload);

    return jsonPayload;
  }

  private void processTrigger(JsonNode payload, String workflowId, String trigger) {
    System.out.println("processTrigger() - Starting");

    // ObjectMapper mapper = new ObjectMapper();
    // JsonNode jsonPayload = mapper.convertValue(payload, JsonNode.class);
    Configuration jacksonConfig =
        Configuration.builder()
            .mappingProvider(new JacksonMappingProvider())
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();
    List<WorkflowAbstractParam> inputProperties = new LinkedList<>();
    // Test String property that exists
    WorkflowAbstractParam flowProperty1 = new WorkflowAbstractParam();
    flowProperty1.setKey("callback_url");
    inputProperties.add(flowProperty1);
    // Test Array property that exists
    WorkflowAbstractParam flowProperty2 = new WorkflowAbstractParam();
    flowProperty2.setKey("push_data.images");
    inputProperties.add(flowProperty2);
    // Test String property that does not exist
    WorkflowAbstractParam flowProperty3 = new WorkflowAbstractParam();
    flowProperty3.setKey("notPresent");
    inputProperties.add(flowProperty3);
    Map<String, String> properties = new HashMap<>();
    if (inputProperties != null) {
      // JsonNode parsedEventData = JsonPath.using(jacksonConfig).parse(payload);
      assertNotNull(inputProperties);
      try {
        inputProperties.forEach(
            inputProperty -> {
              JsonNode propertyValue =
                  JsonPath.using(jacksonConfig).parse(payload).read("$." + inputProperty.getKey());
              // JsonPath.read(parsedEventData, "$." + inputProperty.getKey());

              if (!propertyValue.isNull()) {
                properties.put(inputProperty.getKey(), propertyValue.toString());
              }
            });
      } catch (Exception e) {
        System.out.println("processTrigger() - Error: " + e.toString());
      }

      // Assertions.assertEquals(properties.get("callback_url").toString(),
      // "https://registry.hub.docker.com/u/svendowideit/testhook/hook/2141b5bi5i5b02bec211i4eeih0242eg11000a/");
    }

    properties.put("io.boomerang.triggers.data", payload.toString());

    properties.forEach(
        (k, v) -> {
          System.out.println(k + "=" + v);
        });
  }
}
