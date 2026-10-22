package io.boomerang.integrations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.model.Integration;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.AuthExempt;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * The extensions management controller
 *
 * The inbound Slack routes accept only a request Slack signed (SlackService.verifySignature).
 */
@RestController
@RequestMapping("/api/v2/integration")
@Tag(
    name = "Integrations",
    description = "Handles the integrations with 3rd parties such as Slack and GitHub.")
public class IntegrationControllerV2 {
  private static final Logger LOGGER = LogManager.getLogger();

  // Shared by the three inbound POST routes, which accept only a request Slack signed with the
  // configured signing secret, and the OAuth redirect, which accepts only a code Slack issued for
  // the configured client.
  private static final String SLACK_EXEMPT_REASON =
      "Reached by Slack, which cannot hold a Flow token, so permitAll in SecurityConfiguration and"
          + " skipped by AuthenticationFilter (SLACK_ROUTES); the handler checks the request"
          + " itself - the Slack signature, or the OAuth code exchange against Slack.";

  // The install redirect checks nothing, so it is not on the permit list: it answers only a
  // signed-in caller, with the configured install URL.
  private static final String SLACK_INSTALL_EXEMPT_REASON =
      "Meant for a browser, which may hold a Flow session. Nothing to protect: it redirects to the"
          + " configured install URL and takes no input, but it is kept behind sign-in because it"
          + " carries no check of its own.";

  // The headers Slack signs every request with.
  private static final String SLACK_TIMESTAMP_HEADER = "x-slack-request-timestamp";
  private static final String SLACK_SIGNATURE_HEADER = "x-slack-signature";

  @Autowired private IntegrationService integrationService;

  @Autowired private SlackService slackService;

  @Autowired private GitHubService githubService;

  @Autowired private ObjectMapper objectMapper;

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INTEGRATION,
      assignableScopes = {AuthScope.key, AuthScope.user, AuthScope.session, AuthScope.global})
  @Operation(summary = "Retrieve the integrations and their status within a Workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  List<Integration> get(@RequestParam String workspace) throws IOException {
    return integrationService.get(workspace);
  }

  /*
   * Slack Endpoints
   */

  /**
   * Slack Auth Endpoint
   *
   * @param request
   * @param code
   * @return
   */
  @AuthExempt(reason = SLACK_EXEMPT_REASON)
  @GetMapping(value = "/slack/auth")
  @Operation(summary = "Receive Slack Oauth2 request")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> receiveSlackAuth(HttpServletRequest request, @RequestParam String code) {
    return slackService.handleAuth(code);
  }

  @AuthExempt(reason = SLACK_INSTALL_EXEMPT_REASON)
  @GetMapping(value = "/slack/install")
  @Operation(summary = "Install URL Redirect")
  @ApiResponses(value = {@ApiResponse(responseCode = "302", description = "Found")})
  ResponseEntity<?> installSlack() throws URISyntaxException {
    return slackService.installRedirect();
  }

  @AuthExempt(reason = SLACK_EXEMPT_REASON)
  @PostMapping(
      value = "/slack/commands",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
  @Operation(summary = "Receive Slack Slash Commands")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> receiveSlackCommand(
      HttpServletRequest request,
      @RequestHeader(value = SLACK_TIMESTAMP_HEADER, required = false) String timestamp,
      @RequestHeader(value = SLACK_SIGNATURE_HEADER, required = false) String signature) {
    String body = rawBody(request);
    if (!slackService.verifySignature(signature, timestamp, body)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    CompletableFuture.supplyAsync(slackService.createRunModal(formValues(body)));
    return ResponseEntity.ok().build();
  }

  // https://api.slack.com/reference/interaction-payloads
  @AuthExempt(reason = SLACK_EXEMPT_REASON)
  @PostMapping(
      value = "/slack/interactivity",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
  @Operation(summary = "Receive Slack Interactivity")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> receiveSlackInteractivity(
      HttpServletRequest request,
      @RequestHeader(value = SLACK_TIMESTAMP_HEADER, required = false) String timestamp,
      @RequestHeader(value = SLACK_SIGNATURE_HEADER, required = false) String signature) {
    String body = rawBody(request);
    if (!slackService.verifySignature(signature, timestamp, body)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    JsonNode payload = objectMapper.readTree(formValues(body).get("payload"));
    if (payload.has("type") && "view_submission".equals(payload.get("type").asText())) {
      CompletableFuture.supplyAsync(slackService.executeRunModal(payload));
    } else if (payload.has("type")) {
      LOGGER.error("Unhandled Slack Interactivity Type: " + payload.get("type").asText());
    } else {
      LOGGER.error(
          "Unhandled Slack Interactivity Payload with no Type: " + payload.toPrettyString());
    }
    return ResponseEntity.ok().build();
  }

  // https://api.slack.com/apis/connections/events-api#receiving_events
  @AuthExempt(reason = SLACK_EXEMPT_REASON)
  @PostMapping(
      value = "/slack/events",
      consumes = {MediaType.APPLICATION_JSON_VALUE})
  @Operation(summary = "Receive Slack Events")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> receiveSlackEvent(
      @RequestHeader(value = SLACK_TIMESTAMP_HEADER, required = false) String timestamp,
      @RequestHeader(value = SLACK_SIGNATURE_HEADER, required = false) String signature,
      @RequestBody String body) {
    if (!slackService.verifySignature(signature, timestamp, body)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    JsonNode payload = objectMapper.readTree(body);
    if (payload.has("challenge")) {
      LOGGER.info("Challenge: " + payload.get("challenge"));
      return ResponseEntity.ok().body(payload.get("challenge"));
    } else if (payload.has("type")
        && "app_home_opened".equals(payload.get("event").get("type").asText())) {
      CompletableFuture.supplyAsync(slackService.appHomeOpened(payload));
    } else if (payload.has("type")
        && "app_uninstalled".equals(payload.get("event").get("type").asText())) {
      CompletableFuture.supplyAsync(slackService.appUninstalled(payload));
    } else if (payload.has("type")) {
      LOGGER.error("Unhandled Slack Event Type: " + payload.get("type").asText());
    } else {
      LOGGER.error("Unhandled Slack Event Payload with no Type: " + payload.toPrettyString());
    }
    return ResponseEntity.ok().build();
  }

  // The body exactly as Slack sent it: the signature is over these bytes, so a form route reads the
  // stream itself rather than letting Spring bind parameters. An unreadable body fails the check.
  private static String rawBody(HttpServletRequest request) {
    try {
      return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  // A form-encoded body as name to value, values URL-decoded.
  private static Map<String, String> formValues(String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> pair[0],
                pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "",
                (first, second) -> first));
  }

  /*
   * GitHub Endpoints
   */
  @GetMapping(value = "/github/installation")
  @Operation(summary = "Retrieve the installation ID and store against a workspace")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INTEGRATION,
      assignableScopes = {AuthScope.key, AuthScope.user, AuthScope.session, AuthScope.global})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> githubInstall(
      @RequestParam Optional<Integer> id, @RequestParam Optional<String> workspace) throws IOException {
    if (id.isPresent()) {
      return githubService.getInstallation(id.get());
    } else if (workspace.isPresent()) {
      return githubService.getInstallationForWorkspace(workspace.get());
    }
    return ResponseEntity.badRequest().build();
  }

  @PostMapping(value = "/github/unlink")
  @Operation(summary = "Unlinks the GitHub Installation ID from a Workspace")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.INTEGRATION,
      assignableScopes = {AuthScope.key, AuthScope.user, AuthScope.session, AuthScope.global})
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK")})
  void githubUnlink(@RequestBody GHLinkRequest request) throws IOException {
    githubService.unlinkAppInstallation(request);
  }

  /**
   * GitHub App setup callback. Reached by a browser redirect from GitHub after installation, so
   * it cannot carry a bearer token - its security comes from the signed, short-lived state issued
   * by {@code GET /api/v2/integration} (never trusted unverified), the workspace-membership check,
   * and the installer-ownership check performed against GitHub's own OAuth token exchange.
   * Deliberately unauthenticated (see the exemptions in SecurityConfiguration and
   * AuthenticationFilter) - intentional, unlike the previously-missing @AuthCriteria on unlink.
   */
  @AuthExempt(
      reason =
          "GitHub App setup callback reached by a browser redirect from GitHub, so it carries no"
              + " bearer; secured by the signed short-lived state, the workspace-membership check and"
              + " the installer-ownership check against GitHub's token exchange. permitAll in"
              + " SecurityConfiguration.")
  @GetMapping(value = "/github/callback")
  @Operation(summary = "Receive the GitHub App installation setup callback")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "302", description = "Found"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  ResponseEntity<?> githubCallback(
      @RequestParam(name = "installation_id") Integer installationId,
      @RequestParam(name = "setup_action", required = false) String setupAction,
      @RequestParam String code,
      @RequestParam String state) {
    LOGGER.debug("GitHub callback - installation: {}, setup_action: {}", installationId, setupAction);
    return githubService.handleInstallCallback(installationId, code, state);
  }
}
