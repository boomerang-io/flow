package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.core.config.RestConfig;
import io.boomerang.event.config.EventSinkProperties;
import io.boomerang.event.config.EventSinkProperties.Destination;
import io.boomerang.event.enums.EventPayload;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Sink authentication. A destination may carry its secret in a request header instead of the query
 * string; a bare URL sink is delivered to exactly as before, its URL untouched, so a receiver that
 * can only take a "?token=" keeps working. Delivery goes through the configured internal
 * RestTemplate (proxy, timeouts, trust - see RestConfig).
 */
class EventSinkDeliveryTest {

  private static final String BARE_URL = "http://sink.test/events?token=url-secret";
  private static final String HEADER_URL = "http://secure-sink.test/events";

  @Test
  void aDestinationWithAHeaderSendsItAndLeavesTheUrlAlone() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(requestTo(HEADER_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer s3cret"))
        .andExpect(header("Content-Type", "application/cloudevents+json"))
        .andRespond(withSuccess());

    sinkService(restTemplate, List.of(), List.of(new Destination(HEADER_URL, null, "Bearer s3cret")))
        .deliverStatusCloudEvent(workflowRun());

    server.verify();
  }

  @Test
  void aCustomHeaderNameIsHonoured() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(requestTo(HEADER_URL))
        .andExpect(header("x-api-key", "s3cret"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess());

    sinkService(
            restTemplate, List.of(), List.of(new Destination(HEADER_URL, "x-api-key", "s3cret")))
        .deliverStatusCloudEvent(workflowRun());

    server.verify();
  }

  @Test
  void aBareUrlSinkBehavesExactlyAsBefore() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(requestTo(BARE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess());

    sinkService(restTemplate, List.of(BARE_URL), List.of()).deliverStatusCloudEvent(workflowRun());

    server.verify();
  }

  @Test
  void bothSinkFormsAreDeliveredToAndADisabledSinkDeliversNothing() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server =
        MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    server.expect(requestTo(BARE_URL)).andRespond(withSuccess());
    server.expect(requestTo(HEADER_URL)).andRespond(withSuccess());

    sinkService(
            restTemplate,
            List.of(BARE_URL),
            List.of(new Destination(HEADER_URL, null, "Bearer s3cret")))
        .deliverStatusCloudEvent(workflowRun());
    server.verify();

    server.reset();
    new EventSinkService(
            new EventSinkProperties(false, EventPayload.thin, List.of(BARE_URL), List.of()),
            restTemplate)
        .deliverStatusCloudEvent(workflowRun());
    server.verify();
  }

  @Test
  void deliveryGoesThroughTheConfiguredInternalRestTemplate() {
    Qualifier qualifier =
        EventSinkService.class.getConstructors()[0].getParameters()[1].getAnnotation(
            Qualifier.class);

    assertThat(qualifier).isNotNull();
    assertThat(qualifier.value()).isEqualTo("internalRestTemplate");
  }

  private static EventSinkService sinkService(
      RestTemplate restTemplate, List<String> urls, List<Destination> destinations) {
    return new EventSinkService(
        new EventSinkProperties(true, EventPayload.thin, urls, destinations), restTemplate);
  }

  private static WorkflowRunEntity workflowRun() {
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.setId("wfr-1");
    entity.setWorkflowRef("wf-1");
    entity.setStatus(RunStatus.succeeded);
    entity.setPhase(RunPhase.completed);
    entity.setCreationDate(new Date(0));
    return entity;
  }
}
