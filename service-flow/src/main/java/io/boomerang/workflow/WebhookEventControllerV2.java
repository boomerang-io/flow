package io.boomerang.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.boomerang.workflow.model.SlackEventPayload;
import io.cloudevents.CloudEvent;
import io.cloudevents.spring.http.CloudEventHttpUtils;
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

  public WebhookEventControllerV2(WebhookEventService webhookEventService) {
    this.webhookEventService = webhookEventService;
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
   * <p>Can use Authorization header or access_token URL Parameter
   *
   * <h4>Sample</h4>
   *
   * <code>/webhook?ref={workflow}&access_token={access_token}</code>
   */
  @PostMapping(value = "/webhook", consumes = "application/json; charset=utf-8")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {
        AuthScope.session,
        AuthScope.user,
        AuthScope.global,
        AuthScope.workflow,
        AuthScope.team
      })
  @Operation(summary = "Accept Webhook payloads from various sources.")
  public ResponseEntity<?> acceptWebhook(
      @Parameter(
              name = "ref",
              description = "Workflow reference the request relates to",
              required = false)
          @RequestParam(required = false)
          Optional<String> ref,
      @RequestHeader(value = "X-GitHub-Event", required = false) Optional<String> githubEvent,
      @RequestHeader(value = "X-GitHub-Hook-Installation-Target-ID", required = false)
          Optional<String> githubInstallationId,
      @RequestHeader(value = "x-slack-signature", required = false) Optional<String> slackSignature,
      @RequestBody JsonNode payload,
      HttpServletRequest request) {
    request
        .getHeaderNames()
        .asIterator()
        .forEachRemaining(
            headerName ->
                LOGGER.debug(
                    "Webhook Header::" + headerName + ": " + request.getHeader(headerName)));
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
      webhookEventService.processGitHubWebhook(githubEvent.get(), payload);
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
   * <code>/callback?ref={workflowrun}&topic={topic}&status={status}&access_token={access_token}
   * </code>
   */
  @PostMapping(value = "/callback", consumes = "application/json; charset=utf-8")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {
        AuthScope.session,
        AuthScope.user,
        AuthScope.global,
        AuthScope.workflow,
        AuthScope.team
      })
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
      @RequestBody JsonNode payload) {
    webhookEventService.processWFE(ref, topic, status, Optional.of(payload));
  }

  /**
   * HTTP GET specifically for the "Wait For Event" workflow task.
   *
   * <p>Typically you would use the POST, however this can be useful to trigger from an email to
   * continue or similar.
   *
   * <h4>Sample</h4>
   *
   * <code>/callback?ref={workflowrun}&topic={topic}&status={status}&access_token={access_token}
   * </code>
   */
  @GetMapping(value = "/callback")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {
        AuthScope.session,
        AuthScope.user,
        AuthScope.global,
        AuthScope.workflow,
        AuthScope.team
      })
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
          String status) {
    webhookEventService.processWFE(ref, topic, status, Optional.empty());
  }

  /**
   * Accepts any JSON Cloud Event. This will map to the custom trigger but the topic will come from
   * the CloudEvent subject.
   *
   * <p>ce attributes are in the body
   *
   * @see https://github.com/cloudevents/spec/blob/v1.0/json-format.md
   * @see https://github.com/cloudevents/spec/blob/v1.0/http-protocol-binding.md
   */
  @PostMapping(value = "/event", consumes = "application/cloudevents+json; charset=utf-8")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {
        AuthScope.session,
        AuthScope.user,
        AuthScope.global,
        AuthScope.workflow,
        AuthScope.team
      })
  @Operation(summary = "Accept CloudEvent")
  public ResponseEntity<?> accept(
      @Parameter(
              name = "ref",
              description = "The Workflow reference the request relates to",
              required = false)
          @RequestParam(required = false)
          Optional<String> ref,
      @RequestBody CloudEvent event) {
    return ResponseEntity.ok(webhookEventService.processEvent(event, ref));
  }

  /** Accepts a Cloud Event with ce attributes are in the header */
  @PostMapping("/event")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WEBHOOK,
      assignableScopes = {
        AuthScope.session,
        AuthScope.user,
        AuthScope.global,
        AuthScope.workflow,
        AuthScope.team
      })
  @Operation(summary = "Accept CloudEvent")
  public ResponseEntity<?> acceptEvent(
      @Parameter(
              name = "ref",
              description = "The Workflow reference the request relates to",
              required = false)
          @RequestParam(required = false)
          Optional<String> ref,
      @RequestHeader HttpHeaders headers,
      @RequestBody String data) {
    CloudEvent event = CloudEventHttpUtils.toReader(headers, () -> data.getBytes()).toEvent();
    return ResponseEntity.ok(webhookEventService.processEvent(event, ref));
  }
}
