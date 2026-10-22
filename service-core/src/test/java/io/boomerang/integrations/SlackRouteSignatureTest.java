package io.boomerang.integrations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inbound Slack routes check Slack's signature over the body exactly as sent, and do nothing
 * else for a request that fails it.
 */
class SlackRouteSignatureTest {

  private static final String ROUTES = "/api/v2/integration/slack";
  private static final String TIMESTAMP = "1700000000";
  private static final String SIGNATURE = "v0=abc";

  private final SlackService slackService = mock(SlackService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    IntegrationControllerV2 controller = new IntegrationControllerV2();
    ReflectionTestUtils.setField(controller, "slackService", slackService);
    ReflectionTestUtils.setField(controller, "objectMapper", JsonMapper.builder().build());
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
    when(slackService.createRunModal(anyMap())).thenReturn(() -> true);
    when(slackService.executeRunModal(any())).thenReturn(() -> true);
    when(slackService.appHomeOpened(any())).thenReturn(() -> true);
  }

  @Test
  void anUnsignedInteractionIsRefusedAndRunsNothing() throws Exception {
    String body = "payload=%7B%22type%22%3A%22view_submission%22%7D";
    when(slackService.verifySignature(anyString(), anyString(), anyString())).thenReturn(false);

    mvc.perform(form("/interactivity", body)).andExpect(status().isUnauthorized());

    verify(slackService).verifySignature(SIGNATURE, TIMESTAMP, body);
    verify(slackService, never()).executeRunModal(any());
  }

  @Test
  void aSignedInteractionIsHandled() throws Exception {
    String body = "payload=%7B%22type%22%3A%22view_submission%22%7D";
    when(slackService.verifySignature(SIGNATURE, TIMESTAMP, body)).thenReturn(true);

    mvc.perform(form("/interactivity", body)).andExpect(status().isOk());

    verify(slackService).executeRunModal(any());
  }

  @Test
  void anUnsignedCommandIsRefusedAndOpensNothing() throws Exception {
    String body = "team_id=T1&user_id=U1&text=wf";
    when(slackService.verifySignature(anyString(), anyString(), anyString())).thenReturn(false);

    mvc.perform(form("/commands", body)).andExpect(status().isUnauthorized());

    verify(slackService, never()).createRunModal(anyMap());
  }

  @Test
  void aRequestWithNoSignatureHeadersIsRefused() throws Exception {
    when(slackService.verifySignature(any(), any(), anyString())).thenReturn(false);

    mvc.perform(
            post(ROUTES + "/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("team_id=T1"))
        .andExpect(status().isUnauthorized());

    verify(slackService).verifySignature(eq(null), eq(null), eq("team_id=T1"));
    verify(slackService, never()).createRunModal(anyMap());
  }

  @Test
  void anUnsignedEventIsRefusedAndHandledNot() throws Exception {
    String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"app_home_opened\"}}";
    when(slackService.verifySignature(anyString(), anyString(), anyString())).thenReturn(false);

    mvc.perform(
            post(ROUTES + "/events")
                .header("x-slack-request-timestamp", TIMESTAMP)
                .header("x-slack-signature", SIGNATURE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());

    verify(slackService).verifySignature(SIGNATURE, TIMESTAMP, body);
    verify(slackService, never()).appHomeOpened(any());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder form(
      String route, String body) {
    return post(ROUTES + route)
        .header("x-slack-request-timestamp", TIMESTAMP)
        .header("x-slack-signature", SIGNATURE)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .content(body);
  }
}
