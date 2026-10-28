package io.boomerang.event;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.integrations.model.SlackEventPayload;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventDeserializationException;
import io.cloudevents.http.HttpMessageFactory;
import io.cloudevents.rw.CloudEventRWException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@Tag(
    name = "Webhooks and Events",
    description =
        "Listen for Events or Webhook requests to trigger Workflows and provide the ability to resolve Wait For Event TaskRuns.")
public class WebhookEventControllerV2 {

  private static final Logger LOGGER = LogManager.getLogger();

  private WebhookEventService webhookEventService;
  private final ObjectMapper objectMapper;

  public WebhookEventControllerV2(
      WebhookEventService webhookEventService, ObjectMapper objectMapper) {
    this.webhookEventService = webhookEventService;
    this.objectMapper = objectMapper;
  }

  /**
   * HTTP Webhook accepting Generic, Slack Events, and Dockerhub subtypes. For Slack and Dockerhub
   * will respond/perform verification challenges.
   *
   * <p><b>Note:</b> Partial conformance to the specification.
   *
   * <h4>Specifications</h4>
   *
   * <ul>
   *   <li><a href=
   *       "https://github.com/cloudevents/spec/blob/master/http-webhook.md">CloudEvents</a>
   *   <li><a href="https://docs.docker.com/docker-hub/webhooks/">Dockerhub</a>
   *   <li><a href="https://api.slack.com/events-api">Slack Events API</a>
   *   <li><a href="https://api.slack.com/events">Slack Events</a>
   * </ul>
   *
   * <h4>Sample</h4>
   *
   * <p>Can use an Authorization header, an x-access-token header, or the access_token URL
   * Parameter. The URL parameter is deliberately supported (ruled 2026-09-01): senders such as
   * Docker Hub offer no header or auth configuration at all, so a token in the URL is their only
   * authentication channel.
   *
   * <h4>Sample</h4>
   *
   * <code>/webhook?ref={workflow id}&access_token={access_token}</code>, or
   * <code>/webhook?workspace={workspace}&ref={workflow name}&access_token={access_token}</code>
   */
  @PostMapping(value = "/webhook", consumes = "application/json; charset=utf-8")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global, AuthScope.key})
  @Operation(summary = "Accept Webhook payloads from various sources.")
  public ResponseEntity<?> acceptWebhook(
      @Parameter(
              name = "workspace",
              description = "The workspace whose workflow ref names",
              required = false)
          @RequestParam(required = false)
          Optional<String> workspace,
      @Parameter(
              name = "ref",
              description = "The workflow: its id, or with workspace its name",
              required = false)
          @RequestParam(required = false)
          Optional<String> ref,
      @RequestHeader(value = "X-GitHub-Event", required = false) Optional<String> githubEvent,
      @RequestHeader(value = "X-GitHub-Delivery", required = false) Optional<String> githubDelivery,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) Optional<String> githubSignature,
      @RequestHeader(value = "x-slack-signature", required = false) Optional<String> slackSignature,
      @RequestBody String rawBody,
      HttpServletRequest request) {
    request
        .getHeaderNames()
        .asIterator()
        .forEachRemaining(
            // Names only: a header such as Authorization carries the caller's credential.
            headerName -> LOGGER.debug("Webhook Header::{}", headerName));
    JsonNode payload =
        rawBody == null || rawBody.isBlank() ? null : objectMapper.readTree(rawBody);
    if (slackSignature.isPresent()) {
      if (payload != null) {
        final String slackType = payload.get("type").asText();
        if ("url_verification".equals(slackType)) {
          SlackEventPayload response = new SlackEventPayload();
          final String slackChallenge = payload.get("challenge").asText();
          if (slackChallenge != null) {
            response.setChallenge(slackChallenge);
          }
          return ResponseEntity.ok(response);
        } else if (payload != null
            && ("shortcut".equals(slackType) || "event_callback".equals(slackType))) {
          // Handle Slack Events
          // TODO change this to processSlackWebhook
          return ResponseEntity.ok(webhookEventService.processWebhook(ref.get(), payload));
        } else {
          return ResponseEntity.badRequest().build();
        }
      } else {
        return ResponseEntity.badRequest().build();
      }
    } else if (githubEvent.isPresent()) {
      return webhookEventService.processGitHubWebhook(
          githubEvent.get(), githubDelivery, githubSignature, rawBody, payload);
    } else if (ref.isPresent() && workspace.isPresent()) {
      webhookEventService.processWebhook(workspace.get(), ref.get(), payload);
    } else if (ref.isPresent()) {
      webhookEventService.processWebhook(ref.get(), payload);
    } else {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok().build();
  }

  /**
   * HTTP POST specifically for the "Wait For Event" workflow task.
   *
   * <h4>Sample</h4>
   *
   * <code>/callback?ref={workflowrun}&topic={topic}&status={status}&eventId={eventId}&access_token={access_token}
   * </code>
   */
  @PostMapping(value = "/callback", consumes = "application/json; charset=utf-8")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global, AuthScope.key})
  @Operation(summary = "Accept Wait for Event Callback with JSON Payload")
  public void acceptWaitForEvent(
      @Parameter(
              name = "ref",
              description = "The WorkflowRun reference the request relates to",
              required = true)
          @RequestParam(required = true)
          String ref,
      @Parameter(name = "topic", description = "The topic to publish to", required = true)
          @RequestParam(required = true)
          String topic,
      @Parameter(
              name = "status",
              description = "The status to set for the WaitForEvent TaskRun. Succeeded | Failed.",
              required = false)
          @RequestParam(defaultValue = "succeeded")
          String status,
      @Parameter(
              name = "eventId",
              description =
                  "Identifies this delivery. A repeat with the same eventId for the same run is"
                      + " acknowledged and not applied again, for 7 days.",
              required = false)
          @RequestParam(required = false)
          String eventId,
      @RequestBody JsonNode payload) {
    webhookEventService.processWFE(
        ref, topic, status, Optional.of(payload), Optional.ofNullable(eventId));
  }

  /**
   * HTTP GET specifically for the "Wait For Event" workflow task.
   *
   * <p>Typically you would use the POST, however this can be useful to trigger from an email to
   * continue or similar.
   *
   * <h4>Sample</h4>
   *
   * <code>/callback?ref={workflowrun}&topic={topic}&status={status}&eventId={eventId}&access_token={access_token}
   * </code>
   */
  @GetMapping(value = "/callback")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global, AuthScope.key})
  @Operation(summary = "Accept Wait for Event Callbcak")
  public void acceptWaitForEvent(
      @Parameter(
              name = "ref",
              description = "The WorkflowRun reference the request relates to",
              required = true)
          @RequestParam(required = true)
          String ref,
      @Parameter(name = "topic", description = "The topic to publish to", required = true)
          @RequestParam(required = true)
          String topic,
      @Parameter(
              name = "status",
              description = "The status to set for the WaitForEvent TaskRun. Succeeded | Failed.",
              required = false)
          @RequestParam(defaultValue = "succeeded")
          String status,
      @Parameter(
              name = "eventId",
              description =
                  "Identifies this delivery. A repeat with the same eventId for the same run is"
                      + " acknowledged and not applied again, for 7 days.",
              required = false)
          @RequestParam(required = false)
          String eventId) {
    webhookEventService.processWFE(
        ref, topic, status, Optional.empty(), Optional.ofNullable(eventId));
  }

  /**
   * Accepts a CloudEvent in structured mode ({@code application/cloudevents+json}, attributes in
   * the body) or binary mode (attributes in {@code ce-*} headers, the body its data), as the
   * Content-Type says. {@code ref} names the workflow: its id, or with {@code workspace} its name
   * in that workspace; without {@code ref}, the first segment of the event's subject does.
   *
   * @see https://github.com/cloudevents/spec/blob/v1.0/json-format.md
   * @see https://github.com/cloudevents/spec/blob/v1.0/http-protocol-binding.md
   */
  @PostMapping("/event")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global, AuthScope.key})
  @Operation(summary = "Accept CloudEvent")
  public ResponseEntity<?> acceptEvent(
      @Parameter(
              name = "workspace",
              description = "The workspace whose workflow ref names",
              required = false)
          @RequestParam(required = false)
          Optional<String> workspace,
      @Parameter(
              name = "ref",
              description = "The workflow: its id, or with workspace its name",
              required = false)
          @RequestParam(required = false)
          Optional<String> ref,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) byte[] body) {
    CloudEvent event = readEvent(headers, body == null ? new byte[0] : body);
    return ResponseEntity.ok(
        workspace.isPresent()
            ? webhookEventService.processEvent(workspace.get(), event, ref)
            : webhookEventService.processEvent(event, ref));
  }

  // Read with the plain HTTP binding: the Spring one reads HttpHeaders as a Map, which it no longer is.
  private static CloudEvent readEvent(HttpHeaders headers, byte[] body) {
    try {
      return HttpMessageFactory.createReader(
              header ->
                  headers.forEach((name, values) -> values.forEach(value -> header.accept(name, value))),
              body)
          .toEvent();
    } catch (CloudEventRWException | EventDeserializationException e) {
      throw new BoomerangException(e, BoomerangError.WORKFLOW_INVALID_REQ);
    }
  }
}
