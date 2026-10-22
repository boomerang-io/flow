package io.boomerang.workflow;
//package io.boomerang.service;
//
//import java.net.URI;
//import java.time.ZonedDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.HashMap;
//import java.util.LinkedList;
//import java.util.List;
//import java.util.Map;
//import java.util.Map.Entry;
//import java.util.stream.Collectors;
//import java.util.Optional;
//import org.apache.http.HttpStatus;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//import com.fasterxml.jackson.core.JsonProcessingException;
//import com.fasterxml.jackson.core.type.TypeReference;
//import com.fasterxml.jackson.databind.JsonMappingException;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.fasterxml.jackson.databind.node.TextNode;
//import com.jayway.jsonpath.Configuration;
//import com.jayway.jsonpath.DocumentContext;
//import com.jayway.jsonpath.JsonPath;
//import com.jayway.jsonpath.Option;
//import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
//import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
//import io.boomerang.workflow.model.FlowActivity;
//import io.boomerang.workflow.model.FlowExecutionRequest;
//import io.boomerang.workflow.model.eventing.EventResponse;
//import io.boomerang.mongo.entity.WorkflowEntity;
//import io.boomerang.mongo.model.KeyValuePair;
//import io.boomerang.mongo.model.Triggers;
//import io.boomerang.mongo.model.WorkflowProperty;
//import io.boomerang.service.refactor.TaskService;
//import io.boomerang.util.ParameterMapper;
//import io.boomerang.util.DataAdapterUtil.FieldType;
//import io.cloudevents.CloudEvent;
//import io.cloudevents.v1.AttributesImpl;
//import io.cloudevents.v1.CloudEventBuilder;
//import io.cloudevents.v1.CloudEventImpl;
//import io.cloudevents.v1.http.Unmarshallers;
//
//@Service
//public class EventProcessorImpl implements EventProcessor {
//
//  protected static final String TYPE_PREFIX = "io.boomerang.eventing.";
//
//  private static final Logger logger = LogManager.getLogger(EventProcessorImpl.class);
//
//  @Autowired
//  private WorkflowService workflowService;
//
//  @Autowired
//  private ExecutionService executionService;
//
//  @Autowired
//  private TaskService taskService;
//
//  @Override
//  public CloudEventImpl<EventResponse> processHTTPEvent(Map<String, Object> headers,
//      JsonNode payload) {
//
//    ZonedDateTime now = ZonedDateTime.now();
//    String formattedDate =
//        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS").format(now) + 'Z';
//    JsonNode timeNode = new TextNode(formattedDate);
//    ((ObjectNode) payload).set("time", timeNode);
//
//    logger.info("processHTTPEvent() - Message: " + payload.toPrettyString());
//    logger.info("processHTTPEvent()  - Headers");
//
//    if (headers != null) {
//      for (Entry<String, Object> entry : headers.entrySet()) {
//        logger.info("Key: {} Value: {}", entry.getKey(), entry.getValue());
//      }
//    }
//
//    String requestStatus = getStatusFromPayload(payload);
//    CloudEvent<AttributesImpl, JsonNode> event = Unmarshallers.structured(JsonNode.class)
//        .withHeaders(() -> headers).withPayload(() -> payload.toString()).unmarshal();
//
//    return createResponseEvent(event.getAttributes().getId(), event.getAttributes().getType(),
//        event.getAttributes().getSource(), event.getAttributes().getSubject().orElse(""),
//        event.getAttributes().getTime().orElse(ZonedDateTime.now()),
//        processEvent(event, requestStatus));
//  }
//
//  @Override
//  public void processNATSMessage(String message) {
//    logger.info("processNATSMessage() - Message: " + message);
//
//    Map<String, Object> headers = Map.of("Content-Type", "application/cloudevents+json");
//    String requestStatus = getStatusFromPayload(message);
//
//    CloudEvent<AttributesImpl, JsonNode> event = Unmarshallers.structured(JsonNode.class)
//        .withHeaders(() -> headers).withPayload(() -> message).unmarshal();
//
//    createResponseEvent(event.getAttributes().getId(), event.getAttributes().getType(),
//        event.getAttributes().getSource(), event.getAttributes().getSubject().orElse(""),
//        event.getAttributes().getTime().orElse(ZonedDateTime.now()),
//        processEvent(event, requestStatus));
//  }
//
//  private String getStatusFromPayload(String message) {
//    ObjectMapper mapper = new ObjectMapper();
//    String requestStatus = "success";
//    try {
//      JsonNode messageJson = mapper.readTree(message);
//      if (messageJson.get("status") != null) {
//        requestStatus = messageJson.get("status").asText();
//        logger.debug("Found status in payload: {}", requestStatus);
//      }
//    } catch (JsonMappingException e) {
//      // TODO Auto-generated catch block
//      e.printStackTrace();
//    } catch (JsonProcessingException e) {
//      // TODO Auto-generated catch block
//      e.printStackTrace();
//    }
//    return requestStatus;
//  }
//
//  private String getStatusFromPayload(JsonNode payload) {
//    logger.debug("Extracting status from payload");
//    String requestStatus = "success";
//    if (payload.get("status") != null) {
//      requestStatus = payload.get("status").asText();
//      logger.debug("Found status in payload: {}", requestStatus);
//    }
//    return requestStatus;
//  }
//
//  private CloudEventImpl<EventResponse> createResponseEvent(String id, String type, URI source,
//      String subject, ZonedDateTime time, EventResponse responseData) {
//    final CloudEventImpl<EventResponse> response =
//        CloudEventBuilder.<EventResponse>builder().withId(id).withType(type).withSource(source)
//            .withData(responseData).withSubject(subject).withTime(time).build();
//
//    return response;
//  }
//
//  private EventResponse processEvent(CloudEvent<AttributesImpl, JsonNode> event, String status) {
//    logger.info("processCloudEvent() - Extensions: {}", event.toString());
//    logger.info("processCloudEvent() - Attributes: {}", event.getAttributes().toString());
//    JsonNode eventData = null;
//    if (event.getData().isPresent()) {
//      eventData = event.getData().get();
//      logger.info("processCloudEvent() - Data: {}", eventData.toPrettyString());
//    }
//
//    EventResponse response = new EventResponse();
//
//
//    String subject = event.getAttributes().getSubject().orElse("");
//
//
//    logger.info("Logging extension: {}", event.getExtensions());
//    if (event.getExtensions() != null) {
//      logger.info("Extension size: {}", event.getExtensions().size());
//
//      for (Entry<String, Object> entry : event.getExtensions().entrySet()) {
//        logger.info("Key: {} Value: {}", entry.getKey(), entry.getValue());
//      }
//    } else {
//      logger.info("Extension is empty");
//    }
//
//    logger.info("processCloudEvent() - Subject: {}", subject);
//    if (!subject.startsWith("/")) {
//      logger.error(
//          "processCloudEvent() - Error: subject does not conform to required standard of /{workflowId} followed by /{topic} if custom event");
//      response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
//      response.setStatusMessage(
//          "Event subject does not conform to required standard of /{workflowId} followed by /{topic} if custom event");
//      return response;
//    }
//
//    String workflowId = getWorkflowIdFromSubject(subject);
//    if (workflowId.isBlank()) {
//      logger.error("processCloudEvent() - Error: unable to retrieve workflowId from Event subject");
//      response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
//      response.setStatusMessage("unable to retrieve workflowId from Event subject");
//      return response;
//
//    }
//    logger.info("processCloudEvent() - WorkflowId: {}", workflowId);
//
//    String topic = getTopicFromSubject(subject);
//    String trigger = event.getAttributes().getType().replace(TYPE_PREFIX, "");
//    logger.info("processCloudEvent() - Trigger: " + trigger + ", Topic: " + topic);
//
//    if (isTriggerEnabled(trigger, workflowId, topic)) {
//      logger.info("processCloudEvent() - Trigger(" + trigger + ") is enabled");
//
//      FlowExecutionRequest executionRequest = new FlowExecutionRequest();
//
//      List<KeyValuePair> cloudEventLabels = new LinkedList<>();
//      KeyValuePair property = new KeyValuePair();
//      property.setKey("eventId");
//      property.setValue(event.getAttributes().getId());
//      cloudEventLabels.add(property);
//      // executionRequest.setLabels(cloudEventLabels);
//      executionRequest.setProperties(processProperties(eventData, workflowId));
//
//      FlowActivity activity = executionService.executeWorkflow(workflowId, Optional.of(trigger),
//          Optional.of(executionRequest), Optional.empty());
//      response.setActivityId(activity.getId());
//      response.setStatusCode(HttpStatus.SC_OK);
//      return response;
//    } else if ("wfe".equals(trigger)) {
//      logger.info("processCloudEvent() - Wait For Event System Task");
//      String workflowActivityId = getWorkflowActivityIdFromSubject(subject);
//
//      Map<String, String> outputProperties = new HashMap<>();
//      if (eventData != null) {
//        String json = eventData.toPrettyString();
//        outputProperties.put("eventPayload", json);
//      }
//
//      List<String> taskActivityId =
//          taskService.updateTaskActivityForTopic(workflowActivityId, topic);
//      for (String id : taskActivityId) {
//        taskService.submitActivity(id, status, outputProperties);
//      }
//
//    } else {
//      logger.error("processCloudEvent() - No matching trigger enabled.");
//      response.setStatusCode(HttpStatus.SC_FORBIDDEN);
//      response.setStatusMessage("Event did not match enabled workflow trigger.");
//      return response;
//    }
//
//    return null;
//  }
//
//  /*
//   * Loop through a Workflow's parameters and if a JsonPath is set read the event payload and
//   * attempt to find a payload.
//   * 
//   * Notes: - We drop exceptions to ensure Workflow continues executing - We return null if path not
//   * found using DEFAULT_PATH_LEAF_TO_NULL.
//   * 
//   * Reference: - https://github.com/json-path/JsonPath#tweaking-configuration
//   */
//  private Map<String, String> processProperties(JsonNode eventData, String workflowId) {
//    Configuration jsonConfig = Configuration.builder().mappingProvider(new JacksonMappingProvider())
//        .jsonProvider(new JacksonJsonNodeJsonProvider()).options(Option.DEFAULT_PATH_LEAF_TO_NULL)
//        .build();
//    List<WorkflowProperty> inputProperties =
//        workflowService.getWorkflow(workflowId).getProperties();
//    Map<String, String> properties = new HashMap<>();
//    DocumentContext jsonContext = JsonPath.using(jsonConfig).parse(eventData);
//    if (inputProperties != null) {
//      try {
//        inputProperties.forEach(inputProperty -> {
//          if (inputProperty.getJsonPath() != null && !inputProperty.getJsonPath().isBlank()) {
//
//            JsonNode propertyValue = jsonContext.read(inputProperty.getJsonPath());
//
//            if (!propertyValue.isNull()) {
//              String value = propertyValue.toString();
//              value = value.replaceAll("^\"+|\"+$", "");
//              logger.info("processProperties() - Property: " + inputProperty.getKey()
//                  + ", Json Path: " + inputProperty.getJsonPath() + ", Value: " + value);
//              properties.put(inputProperty.getKey(), value);
//            } else {
//              logger.info("processProperties() - Skipping property: " + inputProperty.getKey());
//            }
//          }
//        });
//      } catch (Exception e) {
//        // Log and drop exception. We want the workflow to continue execution.
//        logger.error(e.toString());
//      }
//    }
//    ObjectMapper mapper = new ObjectMapper();
//    Map<String, String> payloadProperties = mapper.convertValue(eventData.get("properties"),
//        new TypeReference<Map<String, String>>() {});
//    if (payloadProperties != null) {
//      properties.putAll(payloadProperties);
//    }
//
//    // properties.put("eventPayload", eventData.toString());
//
//    WorkflowEntity workflow = workflowService.getWorkflow(workflowId);
//    Map<String, String> finalProperties = new HashMap<>();
//
//    if (!properties.isEmpty()) {
//      List<KeyValuePair> propertyList = ParameterMapper.mapToKeyValuePairList(properties);
//      Map<String, WorkflowProperty> workflowPropMap = workflow.getProperties().stream().collect(
//          Collectors.toMap(WorkflowProperty::getKey, WorkflowProperty -> WorkflowProperty));
//      // Use default value for password-type parameter when user input value is null when executing
//      // workflow.
//      propertyList.stream().forEach(p -> {
//        if (workflowPropMap.get(p.getKey()) != null
//            && FieldType.PASSWORD.value().equals(workflowPropMap.get(p.getKey()).getType())
//            && p.getValue() == null) {
//          p.setValue(workflowPropMap.get(p.getKey()).getDefaultValue());
//        }
//      });
//
//      for (KeyValuePair prop : propertyList) {
//        logger.info("processProperties() - " + prop.getKey() + "=" + prop.getValue());
//        finalProperties.put(prop.getKey(), prop.getValue());
//      }
//    } else {
//
//      for (WorkflowProperty property : workflow.getProperties()) {
//        finalProperties.put(property.getKey(), property.getDefaultValue());
//      }
//    }
//    return finalProperties;
//  }
//
//  private Boolean isTriggerEnabled(String trigger, String workflowId, String topic) {
//
//    Triggers triggers = workflowService.getWorkflow(workflowId).getTriggers();
//
//    switch (trigger) {
//      case "manual":
//        return triggers.getManual().getEnable();
//      case "scheduler":
//        return triggers.getScheduler().getEnable();
//      case "webhook":
//      case "dockerhub":
//      case "slack":
//        return triggers.getWebhook().getEnable();
//      case "custom":
//        if (triggers.getCustom().getEnable()) {
//          return topic
//              .equals(workflowService.getWorkflow(workflowId).getTriggers().getCustom().getTopic());
//        } ;
//    }
//    return false;
//  }
//
//  private String getWorkflowIdFromSubject(String subject) {
//    // Reference 0 will be an empty string as it is the left hand side of the split
//    String[] splitArr = subject.split("/");
//    if (splitArr.length >= 2) {
//      return splitArr[1].toString();
//    } else {
//      return "";
//    }
//  }
//
//  private String getWorkflowActivityIdFromSubject(String subject) {
//    // Reference 0 will be an empty string as it is the left hand side of the split
//    String[] splitArr = subject.split("/");
//    if (splitArr.length >= 3) {
//      return splitArr[2].toString();
//    } else {
//      return "";
//    }
//  }
//
//  private String getTopicFromSubject(String subject) {
//    // Reference 0 will be an empty string as it is the left hand side of the split
//    String[] splitArr = subject.split("/");
//    if (splitArr.length >= 4) {
//      return splitArr[3].toString();
//    } else if (splitArr.length >= 3) {
//      return splitArr[2].toString();
//    } else {
//      return "";
//    }
//  }
//}
