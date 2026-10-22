package io.boomerang.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.Slack;
import com.slack.api.app_backend.SlackSignature.Generator;
import com.slack.api.app_backend.SlackSignature.Verifier;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.ContextBlockElement;
import com.slack.api.model.block.DividerBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import com.slack.api.model.view.View;
import com.slack.api.model.view.View.ViewBuilder;
import com.slack.api.model.view.ViewClose;
import com.slack.api.model.view.ViewSubmit;
import com.slack.api.model.view.ViewTitle;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.SettingsService;
import io.boomerang.error.BoomerangException;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workflow.WorkflowService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/*
 * Handles the Slack app slash command and interactivity interactions
 *
 * The Slack app needs the following oauth scopes: - chat:write - commands - users:read -
 * users:read:email
 *
 * This service depends on the SlackSecurityVerificationFilter
 */
@Service
public class SlackService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String MODAL_TEXT_TAGLINE =
      "The better way to automate with no-code workflow automation.";

  private static final String MODAL_TEXT_FOOTER =
      ":bulb: If your workflows require parameters, make sure they have a default value. You also need to be a registered user with access to the Workflow.";

  private static final String EXTENSION_TYPE = "slack_auth";

  @Value("${flow.apps.flow.url}")
  private String flowAppsUrl;

  private final SettingsService settingsService;
  private final IntegrationsRepository extensionsRepository;
  private final WorkflowService workflowService;

  public SlackService(
      SettingsService settingsService,
      WorkflowService workflowService,
      IntegrationsRepository extensionsRepository) {
    this.settingsService = settingsService;
    this.workflowService = workflowService;
    this.extensionsRepository = extensionsRepository;
  }

  /*
   * Processes a Slash command and generates a Modal for the end user to confirm Workflow Run
   * details
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/interactivity/slash-commands">Slack Slash Commands</a></li>
   * <li><a href="https://api.slack.com/surfaces/modals/using">Slack Modals</a></li> </ul>
   *
   * @param triggerId the Slack Trigger ID
   *
   * @param userId the Slack User ID
   *
   * @param workflowId the user entered workflow ID
   *
   * @return Supplier To be used by the CompletableFuture
   */
  public Supplier<Boolean> createRunModal(Map<String, String> slackEvent) {
    return () -> {
      final String triggerId = slackEvent.get("trigger_id");
      final String userId = slackEvent.get("user_id");
      final String teamId = slackEvent.get("team_id");
      final String workflowId = slackEvent.get("text");
      final String authToken = getSlackAuthToken(teamId);

      ViewBuilder modalViewBuilder =
          View.builder()
              .type("modal")
              .title(
                  ViewTitle.builder().type("plain_text").text("Run Workflow").emoji(true).build())
              .callbackId("workflow-run-modal")
              .privateMetadata(workflowId)
              .close(ViewClose.builder().type("plain_text").text("Close").build());

      Slack slack = Slack.getInstance();
      View modalView;
      Boolean notFound = false;
      Workflow workflow = new Workflow();
      if ("help".equals(workflowId)) {
        modalView = modalViewBuilder.blocks(modalHelpBlocks()).build();
      } else {
        UsersInfoResponse userInfo;
        try {
          userInfo =
              slack.methods(authToken).usersInfo(UsersInfoRequest.builder().user(userId).build());
          LOGGER.debug("User Info: " + userInfo.toString());
        } catch (IOException | SlackApiException e) {
          LOGGER.error(e.toString());
          return false;
        }

        if (userInfo != null
            && userInfo.getUser() != null
            && userInfo.getUser().getProfile() != null) {
          // Trigger workflow Execution and impersonate Slack user
          String userEmail = userInfo.getUser().getProfile().getEmail();
          if (userEmail != null && canExecuteWorkflow(workflowId, userEmail)) {
            workflow = workflowService.get(teamId, workflowId, Optional.empty(), false);
            if (workflow != null) {
              modalViewBuilder.submit(
                  ViewSubmit.builder()
                      .type("plain_text")
                      .text(":point_right: Run it")
                      .emoji(true)
                      .build());
            } else {
              LOGGER.debug("Unable to find Workflow with specified ID (" + workflowId + ")");
              notFound = true;
            }
          } else {
            notFound = true;
          }
        }

        modalView =
            modalViewBuilder
                .blocks(
                    modalRunBlocks(
                        workflowId, workflow.getName(), workflow.getDescription(), notFound))
                .build();
      }
      try {
        ViewsOpenResponse viewResponse =
            slack.methods(authToken).viewsOpen(req -> req.triggerId(triggerId).view(modalView));
        LOGGER.debug(viewResponse.toString());
      } catch (IOException | SlackApiException e) {
        LOGGER.error(e.toString());
        return false;
      }

      return true;
    };
  }

  /*
   * Creates the Blocks that are part of the Run Modal Workflow response
   */
  private List<LayoutBlock> modalRunBlocks(
      String workflowId, String workflowName, String workflowSummary, Boolean notFound) {
    List<LayoutBlock> blocks = new LinkedList<>();
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_title")
            .text(MarkdownTextObject.builder().text("_" + MODAL_TEXT_TAGLINE + "_").build())
            .build());
    blocks.add(
        HeaderBlock.builder()
            .text(PlainTextObject.builder().text("Workflow Details").build())
            .build());
    if (!notFound) {
      blocks.add(
          SectionBlock.builder()
              .text(
                  PlainTextObject.builder()
                      .text(
                          "Confirm the following details and click 'Run It' to trigger the workflow. A message will be sent to you with the activity details.")
                      .build())
              .build());
      blocks.add(
          SectionBlock.builder()
              .text(
                  MarkdownTextObject.builder()
                      .text(
                          "*ID:* "
                              + workflowId
                              + "\n*Name:* "
                              + workflowName
                              + "\n*Summary:* "
                              + workflowSummary)
                      .build())
              .build());
    } else {
      if (workflowId.isEmpty() || workflowId.isBlank()) {
        blocks.add(
            SectionBlock.builder()
                .blockId("workflow_fields")
                .text(
                    MarkdownTextObject.builder()
                        .text(
                            ":slightly_frowning_face: It looks like no Workflow ID was provided. Pass `help` into the Slack command for guidance.")
                        .build())
                .build());

      } else {
        blocks.add(
            SectionBlock.builder()
                .blockId("workflow_fields")
                .text(
                    MarkdownTextObject.builder()
                        .text(
                            ":slightly_frowning_face: Unfortunately we are unable to find a Workflow with the specified ID ("
                                + workflowId
                                + "), or you do not have the necessary permissions.")
                        .build())
                .build());
      }
    }
    blocks.add(DividerBlock.builder().build());
    List<ContextBlockElement> elementsList = new LinkedList<>();
    elementsList.add(MarkdownTextObject.builder().text(MODAL_TEXT_FOOTER).build());
    blocks.add(ContextBlock.builder().elements(elementsList).build());
    return blocks;
  }

  /*
   * Creates the Blocks that are part of the Run Modal Help response
   */
  private List<LayoutBlock> modalHelpBlocks() {
    List<LayoutBlock> blocks = new LinkedList<>();
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_title")
            .text(MarkdownTextObject.builder().text("_" + MODAL_TEXT_TAGLINE + "_").build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                PlainTextObject.builder()
                    .text("Learn how to use this Slack app to execute your no-code automation.")
                    .build())
            .build());
    blocks.add(
        HeaderBlock.builder()
            .text(PlainTextObject.builder().text("Available Commands").build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .blockId("command_fields")
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "In Slack, you can pass the following into the command\n\n"
                            + " - `help` - show usage information\n"
                            + " - `{workflowId}` - run a particular workflow\n")
                    .build())
            .build());
    blocks.add(
        HeaderBlock.builder().text(PlainTextObject.builder().text("Next Steps").build()).build());
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_fields")
            .text(
                MarkdownTextObject.builder()
                    .text(
                        ":bulb: If your workflows require parameters, make sure they have a default value. You also need to be a registered user with access to the Workflow.\n\nYou can find this ID by exporting your Workflow and opening up the downloaded JSON file.\n")
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_fields_2")
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "In a future version we hope to enrich the experience by allowing you to choose from the Workflows that you have access to, and also be able to provide the relevant inputs to the Workflow.")
                    .build())
            .build());
    List<BlockElement> buttonsList = new LinkedList<>();
    buttonsList.add(
        ButtonElement.builder()
            .text(
                PlainTextObject.builder()
                    .text(":robot_face: View Your Workflows")
                    .emoji(true)
                    .build())
            .url(flowAppsUrl + "/workflows/mine")
            .build());
    buttonsList.add(
        ButtonElement.builder()
            .text(PlainTextObject.builder().text(":book: Documentation").emoji(true).build())
            .url("https://www.useboomerang.io/docs/boomerang-flow/introduction/overview")
            .build());
    blocks.add(ActionsBlock.builder().elements(buttonsList).build());
    blocks.add(DividerBlock.builder().build());
    List<ContextBlockElement> elementsList = new LinkedList<>();
    elementsList.add(MarkdownTextObject.builder().text(MODAL_TEXT_FOOTER).build());
    blocks.add(ContextBlock.builder().elements(elementsList).build());
    return blocks;
  }

  /*
   * Processes the Submitted Run Modal from Slack, executes the Workflow, and responds with a
   * message to the user
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/reference/interaction-payloads">Interactivity Payloads</a></li>
   * <li><a href="https://api.slack.com/methods/chat.postMessage#formatting">Slack
   * chat.postMessage API</a></li> </ul>
   *
   * @param jsonPayload the mapped payload from the interactivity endpoint
   *
   * @return Supplier To be used by the CompletableFuture
   */
  public Supplier<Boolean> executeRunModal(JsonNode jsonPayload) {
    return () -> {
      // public SlackResponseActionModel executeRunModal(JsonNode jsonPayload) {
      Exception exception = null;
      final String userId = jsonPayload.get("user").get("id").asText();
      LOGGER.debug("User ID: " + userId);
      final String workflowId = jsonPayload.get("view").get("private_metadata").asText();
      LOGGER.debug("Workflow ID: " + workflowId);
      final String teamId = jsonPayload.get("team").get("id").asText();
      final String authToken = getSlackAuthToken(teamId);

      Slack slack = Slack.getInstance();
      try {
        UsersInfoResponse userInfo =
            slack.methods(authToken).usersInfo(UsersInfoRequest.builder().user(userId).build());
        LOGGER.debug("User Info: " + userInfo.toString());
        if (userInfo != null
            && userInfo.getUser() != null
            && userInfo.getUser().getProfile() != null) {
          // Trigger workflow Execution and impersonate Slack user
          String userEmail = userInfo.getUser().getProfile().getEmail();
          if (userEmail != null && canExecuteWorkflow(workflowId, userEmail)) {
            WorkflowSubmitRequest request = new WorkflowSubmitRequest();
            request.setTrigger(TriggerEnum.webhook);
            WorkflowRun workflowRun = workflowService.submit(teamId, workflowId, request, true);
            LOGGER.debug(workflowRun.toString());

            // ChatPostMessageResponse messageResponse = slack.methods(authToken)
            // .chatPostMessage(req -> req.channel(userId)
            // .blocks(activityBlocks(workflowId, workflow.getName(),
            // workflowRun.getShortDescription(), workflowRun.getId())));
            // TODO figure out how to get the WorkflowName and Description into Slack

            ChatPostMessageResponse messageResponse =
                slack
                    .methods(authToken)
                    .chatPostMessage(
                        req ->
                            req.channel(userId)
                                .blocks(activityBlocks(workflowId, "", "", workflowRun.getId())));
            LOGGER.debug(messageResponse.toString());
          } else {
            throw new RuntimeException(
                ":slightly_frowning_face: Unfortunately we are unable to find a Workflow with the specified ID ("
                    + workflowId
                    + "), or you do not have the neceesary permissions.");
          }
        } else {
          throw new RuntimeException(
              "Unable to retrieve a matching User Profile to use when executing the Workflow.");
        }
      } catch (RuntimeException | IOException | SlackApiException e) {
        LOGGER.error(e);
        exception = e;
      }

      if (exception != null) {
        try {
          String message = exception.getMessage();
          ChatPostMessageResponse messageResponse =
              slack
                  .methods(authToken)
                  .chatPostMessage(req -> req.channel(userId).blocks(activityErrorBlocks(message)));
          LOGGER.debug(messageResponse.toString());
        } catch (IOException | SlackApiException e2) {
          LOGGER.error(e2);
        }
        return false;
      }

      return true;
    };
  }

  private Boolean canExecuteWorkflow(String workflowId, String email) {
    //   relationshipService.
    //   if (filterService.getFilteredWorkflowIdsForUserEmail(Optional.empty(), Optional.empty(),
    //       Optional.empty(), email).contains(workflowId)) {
    //     LOGGER.debug("Can execute Workflow");
    //     return true;
    //   }
    //   LOGGER.debug("No matching workflow found for User");
    return false;
  }

  /*
   * Creates the Blocks that are used with returning an error to the User
   */
  private List<LayoutBlock> activityErrorBlocks(String message) {
    List<LayoutBlock> blocks = new LinkedList<>();
    blocks.add(
        HeaderBlock.builder()
            .text(PlainTextObject.builder().text("Workflow Activity").build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_title")
            .text(PlainTextObject.builder().text(":slightly_frowning_face: " + message).build())
            .build());
    blocks.add(DividerBlock.builder().build());
    List<ContextBlockElement> elementsList = new LinkedList<>();
    elementsList.add(
        MarkdownTextObject.builder()
            .text(
                ":bulb: If your workflows require parameters, make sure they have a default value. You also need to be a registered user with access to the Workflow.")
            .build());
    blocks.add(ContextBlock.builder().elements(elementsList).build());
    return blocks;
  }

  /*
   * Creates the Blocks that are part of the Workflow Activity Slack message after a Run Modal is
   * submitted in slack
   */
  private List<LayoutBlock> activityBlocks(
      String workflowId, String workflowName, String workflowSummary, String activityId) {
    List<LayoutBlock> blocks = new LinkedList<>();
    blocks.add(
        HeaderBlock.builder()
            .text(PlainTextObject.builder().text("Workflow Activity").build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .blockId("workflow_title")
            .text(
                PlainTextObject.builder()
                    .text("You requested a workflow to be run with the following details.")
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "*ID:* "
                            + workflowId
                            + "\n*Name:* "
                            + workflowName
                            + "\n*Summary:* "
                            + workflowSummary)
                    .build())
            .build());
    List<BlockElement> buttonsList = new LinkedList<>();
    buttonsList.add(
        ButtonElement.builder()
            .text(PlainTextObject.builder().text(":dart: View Activity").emoji(true).build())
            .url(flowAppsUrl + "/activity/" + workflowId + "/execution/" + activityId)
            .build());
    buttonsList.add(
        ButtonElement.builder()
            .text(PlainTextObject.builder().text(":book: Documentation").emoji(true).build())
            .url("https://www.useboomerang.io/docs/boomerang-flow/introduction/overview")
            .build());
    blocks.add(ActionsBlock.builder().elements(buttonsList).build());
    blocks.add(DividerBlock.builder().build());
    List<ContextBlockElement> elementsList = new LinkedList<>();
    elementsList.add(MarkdownTextObject.builder().text(MODAL_TEXT_FOOTER).build());
    blocks.add(ContextBlock.builder().elements(elementsList).build());
    return blocks;
  }

  /*
   * Handles the Slack OAuth2 redirect flow. Upon successful code / token exchange will redirect
   * user to the slack app in the workspace.
   */
  public ResponseEntity<?> handleAuth(String code) {
    final String appId = settingsService.getSettingConfig("integration", "slack.appId").getValue();
    final String clientId =
        settingsService.getSettingConfig("integration", "slack.clientId").getValue();
    final String clientSecret =
        settingsService.getSettingConfig("integration", "slack.clientSecret").getValue();

    Slack slack = Slack.getInstance();
    try {
      OAuthV2AccessResponse authResponse =
          slack
              .methods()
              .oauthV2Access(req -> req.clientId(clientId).clientSecret(clientSecret).code(code));
      LOGGER.debug("Auth Response: " + authResponse.toString());
      if (authResponse.isOk()) {
        saveSlackAuthToken(authResponse);
      }
      // TODO: return different response if not ok and redirect somewhere else.
      return ResponseEntity.status(HttpStatus.FOUND)
          .location(new URI("slack://app?team=" + authResponse.getTeam().getId() + "&id=" + appId))
          .build();
    } catch (IOException | SlackApiException | URISyntaxException e) {
      LOGGER.error(e.toString());
    }

    return ResponseEntity.ok().build();
  }

  /*
   * Helper method to save the Slack Auth. It will check if the team already has an auth object.
   *
   * @param OAuthV2AccessResponse
   */
  private void saveSlackAuthToken(OAuthV2AccessResponse authResponse) {
    IntegrationsEntity authExtension = new IntegrationsEntity();
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> payload =
        mapper.convertValue(authResponse, new TypeReference<Map<String, Object>>() {});

    // Optional<IntegrationEntity> origAuthExtension =
    // extensionsRepository.findByType(EXTENSION_TYPE).stream()
    // .filter(e -> e.getLabels().indexOf(teamIdLabel) != -1).findFirst();
    List<IntegrationsEntity> authsList = checkExistingAuthExtension(authResponse.getTeam().getId());
    if (!authsList.isEmpty()) {
      LOGGER.debug("Overriding existing Slack Team Auth");
      authExtension = authsList.get(0);
      authExtension.setData(payload);
    } else {
      LOGGER.debug("Saving new Slack Team Auth");
      authExtension.setType(EXTENSION_TYPE);
      authExtension.setData(payload);
      Map<String, String> labels = new HashMap<>();
      labels.put("teamId", authResponse.getTeam().getId());
      authExtension.setLabels(labels);
    }
    extensionsRepository.save(authExtension);
    LOGGER.debug(authExtension.toString());
  }

  /*
   * Helper method to save the user onto the Auth Extension. This means that the user has opened
   * the App before.
   *
   * @param OAuthV2AccessResponse
   */
  private void addUserToAuthExtension(String teamId, String userId) {
    List<IntegrationsEntity> authExtensions = new LinkedList<>();
    extensionsRepository
        .findByType(EXTENSION_TYPE)
        .forEach(
            e -> {
              Map<String, String> executionProperties = e.getLabels();
              if (executionProperties.containsKey("teamId")
                  && teamId.equals(executionProperties.get("teamId"))) {
                authExtensions.add(e);
                LOGGER.debug("Found matching Slack Team Extension");
              }
            });
    if (!authExtensions.isEmpty()) {
      IntegrationsEntity authExtension = authExtensions.get(0);
      //            authExtension.getUsers().add(userId);
      extensionsRepository.save(authExtension);
      LOGGER.debug("Added user to Slack Team Extension");
      LOGGER.debug(authExtension.toString());
    }
  }

  /*
   * Helper method to retrieve a saved Auth for a Slack Team (org)
   */
  private List<IntegrationsEntity> checkExistingAuthExtension(String teamId) {
    List<IntegrationsEntity> authExtensions = new LinkedList<>();
    extensionsRepository
        .findByType(EXTENSION_TYPE)
        .forEach(
            e -> {
              Map<String, String> executionProperties = e.getLabels();
              if (executionProperties.containsKey("teamId")
                  && teamId.equals(executionProperties.get("teamId"))) {
                authExtensions.add(e);
                LOGGER.debug("Found matching Slack Team Auth");
              }
            });
    return authExtensions;
  }

  /*
   * Helper method to check if a User has opened the App as part of an Extension
   */
  private Boolean checkExistingAuthExtensionForUser(String teamId, String userId) {
    List<IntegrationsEntity> authExtensions = new LinkedList<>();
    extensionsRepository
        .findByType(EXTENSION_TYPE)
        .forEach(
            e -> {
              Map<String, String> executionProperties = e.getLabels();
              if (executionProperties.containsKey("teamId")
                  && teamId.equals(executionProperties.get("teamId"))) {
                authExtensions.add(e);
                LOGGER.debug("Found matching Slack Team Extension");
              }
            });
    if (!authExtensions.isEmpty()) {
      IntegrationsEntity authEntity = authExtensions.get(0);
      //            if (authEntity.getUsers().contains(userId)) {
      //                LOGGER.debug("Found matching UserId on Team Auth Extension");
      //                return true;
      //            }
    }
    return false;
  }

  /*
   * & Helper method to retrieve the stored Slack Token using the extensions generic collection
   *
   * @param teamId
   */
  private String getSlackAuthToken(String teamId) {
    List<IntegrationsEntity> authsList = checkExistingAuthExtension(teamId);
    if (!authsList.isEmpty()) {
      Map<String, Object> data = (Map<String, Object>) authsList.get(0).getData();
      String teamAuthToken = data.get("accessToken").toString();
      LOGGER.debug("Using existing team Slack auth token: " + teamAuthToken);
      return teamAuthToken;
    }
    String defaultAuthToken =
        settingsService.getSettingConfig("integration", "slack.token").getValue();
    LOGGER.debug("Using default Slack auth token: " + defaultAuthToken);
    return defaultAuthToken;
  }

  /*
   * Scaffold for a future Interactivity Method
   *
   * <h4>Specifications</h4> <ul> <li><a href=""></a></li> </ul>
   *
   * @param jsonPayload the mapped payload from the interactivity endpoint
   *
   * @return Supplier To be used by the CompletableFuture
   */
  // public Supplier<Boolean> executeRunModal(JsonNode jsonPayload) {
  // return () -> {
  // public SlackResponseActionModel executeRunModal(JsonNode jsonPayload) {

  //
  // View updatedView = View.builder().type("modal")
  // .title(ViewTitle.builder().type("plain_text").text("Run Workflow").emoji(true).build())
  // .callbackId("workflow-run-modal").privateMetadata(workflowId).blocks(executeBlocks(workflowId,
  // flowActivity.getWorkflowName(), flowActivity.getShortDescription(), flowActivity.getId()))
  // .close(ViewClose.builder().type("plain_text").text("Close").build()).build();
  // try {
  // ViewsUpdateResponse viewResponse = slack.methods(authToken)
  // .viewsUpdate(req -> req.viewId(rootViewId).view(updatedView));
  // LOGGER.info(viewResponse.toString());
  // } catch (IOException | SlackApiException e) {
  // LOGGER.error(e.toString());
  // return false;
  // }
  // return true;
  // };
  // }

  /*
   * Processes the Submitted Run Modal from Slack, executes the Workflow, and responds with a
   * message to the user
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/methods/chat.postMessage#formatting">Slack chat.postMessage
   * API</a></li> </ul>
   *
   * @param jsonPayload the mapped payload from the interactivity endpoint
   *
   * @return Supplier To be used by the CompletableFuture
   */
  public Supplier<Boolean> appHomeOpened(JsonNode jsonPayload) {
    return () -> {
      LOGGER.debug("Payload: " + jsonPayload.toPrettyString());
      final String userId = jsonPayload.get("event").get("user").asText();
      final String teamId = jsonPayload.get("team_id").asText();
      final String authToken = getSlackAuthToken(teamId);

      Slack slack = Slack.getInstance();
      try {
        UsersInfoResponse userInfo =
            slack.methods(authToken).usersInfo(UsersInfoRequest.builder().user(userId).build());
        LOGGER.debug("User Info: " + userInfo.toString());
        if (userInfo != null && userInfo.getUser() != null && userInfo.getUser().getId() != null) {
          String slackUserId = userInfo.getUser().getId();
          if (checkExistingAuthExtensionForUser(teamId, userInfo.getUser().getId())) {
            LOGGER.debug("User has already opened the app, skipping Slack welcome");
          } else {
            addUserToAuthExtension(teamId, slackUserId);
            slack
                .methods(authToken)
                .chatPostMessage(req -> req.channel(userId).blocks(appHomeBlocks()));
          }
        }
      } catch (IOException | SlackApiException | HttpClientErrorException | BoomerangException e) {
        LOGGER.error(e);
        return false;
      }

      return true;
    };
  }

  /*
   * Creates the Blocks that are part of the Workflow Activity Slack message after a Run Modal is
   * submitted in slack
   */
  private List<LayoutBlock> appHomeBlocks() {
    String appName = settingsService.getSettingConfig("customizations", "appName").getValue();
    String platformName =
        settingsService.getSettingConfig("customizations", "platformName").getValue();
    String joinedName = platformName + " " + appName;
    List<LayoutBlock> blocks = new LinkedList<>();
    blocks.add(
        HeaderBlock.builder()
            .text(PlainTextObject.builder().text("Hello :wave:").emoji(true).build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "Welcome to the new modern and easy way to supercharge your automation. I am here to help you trigger your Workflows from right within Slack, giving you complete control over what needs to be done.\n\nHere are a few things to get started with to make your experience a good one.")
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "*Sign Up*\n"
                            + "Ensure you a user of "
                            + joinedName.trim()
                            + " to be able to automate.")
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "*Create Automation*\n"
                            + "Creating your first workflow is simple. Start from scratch or a template and start dragging and dropping your way to automation.")
                    .build())
            .accessory(
                ButtonElement.builder()
                    .text(
                        PlainTextObject.builder()
                            .text(":rocket: Create Automation")
                            .emoji(true)
                            .build())
                    .url(flowAppsUrl)
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text(
                        "*Use Templates*\n"
                            + "We have a number of handy, pre-built workflow templates for common use cases to get you started quickly. This can be found in the top right Templates button.")
                    .build())
            .accessory(
                ButtonElement.builder()
                    .text(
                        PlainTextObject.builder()
                            .text(":jigsaw: Use a Template")
                            .emoji(true)
                            .build())
                    .url(flowAppsUrl)
                    .build())
            .build());
    blocks.add(
        SectionBlock.builder()
            .text(
                MarkdownTextObject.builder()
                    .text("Please drop us a message at hello@flowabl.io - chat soon,\nTyson")
                    .build())
            .build());
    blocks.add(DividerBlock.builder().build());
    List<ContextBlockElement> elementsList = new LinkedList<>();
    elementsList.add(MarkdownTextObject.builder().text(MODAL_TEXT_FOOTER).build());
    blocks.add(ContextBlock.builder().elements(elementsList).build());
    return blocks;
  }

  /*
   * Processes the App Uninstalled event from Slack and deletes the Slack Auth Extension
   *
   * This also ensures that any previously recorded App_Home_Opened events are cleared for next
   * installation.
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/events/app_uninstalled</a></li> </ul>
   *
   * @param jsonPayload the mapped payload from the interactivity endpoint
   *
   * @return Supplier To be used by the CompletableFuture
   */
  public Supplier<Boolean> appUninstalled(JsonNode jsonPayload) {
    return () -> {
      final String teamId = jsonPayload.get("team_id").asText();
      extensionsRepository
          .findByType(EXTENSION_TYPE)
          .forEach(
              e -> {
                Map<String, String> executionProperties = e.getLabels();
                if (executionProperties.containsKey("teamId")
                    && teamId.equals(executionProperties.get("teamId"))) {
                  extensionsRepository.delete(e);
                  LOGGER.debug("Deleted Slack Team: " + executionProperties.get("teamId"));
                }
              });
      return true;
    };
  }

  /*
   * Utility method for responding to the install redirect query needed for a Slack App
   */
  public ResponseEntity<?> installRedirect() throws URISyntaxException {
    final String installURL =
        settingsService.getSettingConfig("integration", "slack.installURL").getValue();
    return ResponseEntity.status(HttpStatus.FOUND).location(new URI(installURL)).build();
  }

  /*
   * Utlity method for verifying requests are signed by Slack
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/authentication/verifying-requests-from-slack">Verifying Requests
   * from Slack</a></li> </ul>
   */
  public Boolean verifySignature(String signature, String timestamp, String body) {
    String key =
        this.settingsService.getSettingConfig("integration", "slack.signingSecret").getValue();
    LOGGER.debug("Key: " + key);
    LOGGER.debug("Slack Timestamp: " + timestamp);
    LOGGER.debug("Slack Body: " + body);
    Generator generator = new Generator(key);
    Verifier verifier = new Verifier(generator);
    LOGGER.debug("Slack Signature: " + signature);
    LOGGER.debug("Computed Signature: " + generator.generate(timestamp, body));
    return verifier.isValid(timestamp, body, signature);
  }
}
