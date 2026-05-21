package io.boomerang.tests;

import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.boomerang.model.ApprovalRequest;
import io.boomerang.model.FlowActivity;
import io.boomerang.model.FlowExecutionRequest;
import io.boomerang.model.FlowWebhookResponse;
import io.boomerang.model.ListActionResponse;
import io.boomerang.model.RequestFlowExecution;
import io.boomerang.model.teams.Action;


public abstract class IntegrationTests extends AbstractFlowTests {

  private static final Logger LOGGER = LogManager.getLogger("IntegrationTests");
  protected MockRestServiceServer mockServer;

  public static final String AUTHORIZATION_HEADER = "Authorization";
  public static final String TOKEN_PREFIX = "Bearer ";

  protected String BEARER_TOKEN =
      "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0cmJ1bGFAdXMuaWJtLmNvbSIsImF0X2hhc2giOiJlUzBlcy12MHdWQ3dNNDdQaVItZ0ZBIiwicmVhbG1OYW1lIjoiVzNJRFJlYWxtIiwiaXNzIjoiaHR0cHM6Ly93M2lkLmFscGhhLnNzby5pYm0uY29tL2lzYW0iLCJhdWQiOiJNemsxTXpObE16a3ROell4TXkwMCIsImV4cCI6MTgyNDY4OTY2MSwiaWF0IjoxNTI0NTk4MjIwLCJuYmYiOjE1MjQ1OTgxMDAsImVtYWlsQWRkcmVzcyI6InRyYnVsYUB1cy5pYm0uY29tIiwibGFzdE5hbWUiOiJSb3kiLCJibHVlR3JvdXBzIjpbIk1hbmFnZXJzYW5kSVNTdXBwb3J0IiwiV1BQTCUyMFVTJTIwTm9uLUxlbm92byUyMEVtcGxveWVlcyIsIklUU0FTJTIwRHluYW1pYyUyME1hbmFnZXJzIiwiSUJNTWFuYWdlcnMiLCJNYW5hZ2VycyUyMCUyNiUyMEhSJTIwUGFydG5lcnMiLCJpYm1sZWFybmluZyIsIklUU0FTJTIwR2VuZXJhbCUyMEFjY2VzcyUyMDIiLCJ1c2VyJTIwLSUyMHBlcmYiLCJXV19NR1JfRElTVCIsIkxBX1N0YXRzIiwiQkhQRU9DQVRTIiwiSUJNJTIwTWFuYWdlcnMiLCJFT0RfQ0NfQ29nbm9zX0dyb3VwIiwiMTM5TWdyIiwidGhpbmttYW5hZ2VtZW50LXByb2QiLCJBbGwlMjBJQk0lMjBNYW5hZ2VycyUyMChZKSIsIkNUUkVHbG9iYWxNZ3JzIiwiQkhQRU9FVkVSWU9ORSIsIm1hYyIsIkdsb2JhbE1ncnNDb21wU291cmNlIiwiSFJXZWJDb21wU291cmNlIiwiaHIuYWxsbWdycyIsIkhSRUNNV1dNYW5hZ2VycyIsIkhSRUNNV1dNYW5hZ2Vyc0FuZEhSV2ViU3VwcG9ydCIsIkNUUkVHbG9iYWxNZ3JzUGFyZW50IiwiSFJFQ01XV01hbmFnZXJzQW5kSFJBbmRIUldlYlN1cHBvcnQiLCJIUkVDTVdXTWFuYWdlcnNBbmRNYW5hZ2VyU3VwcG9ydCIsIkJIU0FQTUdSR1JQIiwiY29tLmlibS50YXAubWxkYi5hY2Nlc3MuaW50ZXJuYWwuYXV0by51cyIsImNvbS5pYm0udGFwLm1sZGIuYWNjZXNzLmludGVybmFsIiwiY29tLmlibS50YXAubWxkYi5hY2Nlc3MubmV3IiwiQkhQRU9TVUJFWFAiLCJCSFBFT0FQUFJERUwiLCJNU19fVklTSU9fX18yMDEzU0VfX0MiLCJNU19fVklTSU9fX18yMDEwU0VfX01BU1RFUiIsIk1TX19WSVNJT19fXzIwMDdTRV9fTUFTVEVSIiwiTVNfX1ZJU0lPX19fMjAxM1NFX19NQVNURVIiLCJJQk0lMjBVUyUyMFJlZ3VsYXJzIiwiTElTJTIwUmVndWxhciUyMFVTIiwibGVnYWxpYm0iLCJhaHVzZXIiLCJPU1BGX01BTkFHRVIiLCJNQU5BR0VSX09TUEYiLCJNSF9USElOS21hbmFnZW1lbnQiLCJBU0VBTiUyMFBlb3BsZSUyME1hbmFnZXJzX0VuZ2FnZW1lbnRfSW5kb25lc2lhIiwicmVjZWl2SWRHcm91cCIsIlVTTWFuYWdlcnNDNEciLCJjb2dub3MucHJvZC5oci53cGEuY29tLnVzIiwiRFNUQVBQTEVJQk0iLCJ3dy1tdXJhbGx5IiwiR2l0TGFiQWNjZXNzIiwiU0wzMjI4OTRfdnBub25seSIsIkRTVCUyMFNvZnRMYXllciUyMEludGVybmFsJTIwc3ViQWNjdHMiLCJTQ19QUk9YWV80X0dST1VQIiwiV0lOX1ZNX01BQ19NQVNURVIiLCJNU19XSU44X01BQyIsIlZNX0ZVU0lPTl9QUk84X01BQyIsIk1GcyUyMGZvciUyMGlPUyUyMC0lMjBubyUyMEpwIiwiQXBwbGVHYXJhZ2VHaXRsYWIiLCJ3dy1zbGFjay1zd2lmdCIsIldXSERDLmJhc2UiLCJUUF9HQlMyIiwiVFBfdXNlcnMiLCJ3dy1wYWdlcmR1dHkiLCJ3dy1pbnZpc2lvbi1yZXZpZXdlci1DQUlPZmZlcmluZ3NSZXZpZXciLCJ3dy1pbnZpc2lvbi1yZXZpZXdlciIsIm5vbi1kc3RJQ0QiLCJ0ZXN0ZGF5by1kYXlvIiwiV1dIREMucnRwLmJvb21lcmFuZyIsIldXSERDLlJUUCIsIklTJTIwV1NSIiwiSVMlMjBXU1IlMjBNYW5hZ2VycyUyMC0lMjBVUyIsIlNlYXJjaExpZ2h0IiwiUEFDX0FVRElUX01BTkFHRVIyIiwiY2I1IiwiZ2JzYWxsdGVjaCIsImludmlzaW9udXNlcnMiLCJib29tZXJhbmdwbGF0Zm9ybS1pY3AtZ2JzLWVuZ2luZWVyaW5nIiwiYm9vbWVyYW5ncGxhdGZvcm0taWNwLWlzYXAiLCJ3dy1pbnZpc2lvbi1pYm1kZXNpZ24yMDE4cHVyY2hhc2UiLCJ3dy1pbnZpc2lvbi1pYm1iZXRhIiwid3ctaW52aXNpb24tYWxsIl0sImNsaWVudElQIjoiMTYyLjE1OC42Mi41MCIsImF1dGhNZXRob2QiOiJleHQtYXV0aC1pbnRlcmZhY2UiLCJ1c2VyQWdlbnQiOiJNb3ppbGxhLzUuMCAoTWFjaW50b3NoOyBJbnRlbCBNYWMgT1MgWCAxMC4xMzsgcnY6NjAuMCkgR2Vja28vMjAxMDAxMDEgRmlyZWZveC82MC4wIiwiY24iOiJUWVNPTiUyMFcuJTIwTEFXUklFIiwiZG4iOiJ1aWQ9NEc3NjA5ODk3LGM9dXMsb3U9Ymx1ZXBhZ2VzLG89aWJtLmNvbSIsInVpZCI6IjRHNzYwOTg5NyIsImZpcnN0TmFtZSI6Ik1BUkNVUyIsImp0aSI6IjE4NDRkN2UwLTI1OTEtNGMxMi1hYzgzLThiNThlYmMxNmIxMSJ9.SLQViFy9RvcYIDlrhrrlQ72WFcGlKv6qxiPBYki3dZc";


  public RestTemplate testRestTemplate;

  @Autowired
  @Qualifier("internalRestTemplate")
  protected RestTemplate restTemplate;

  @LocalServerPort
  public int port;

  @Override
  protected String[] getCollections() {
    return new String[] {"core_access_tokens", "core_groups_higher_level",
        "core_groups_lower_level", "core_tool_templates", "core_tools", "core_users",
        "core_settings", "core_audit", "requests_creategroup", "requests_createtool",
        "requests_removegroup", "requests_leavetool", "flow_teams", "flow_workflows",
        "flow_workflows_activity", "flow_workflows_activity_task", "flow_workflows_revisions",
        "flow_task_templates", "flow_settings", "flow_workflows_activity_approval"};
  }

  @Override
  protected Map<String, List<String>> getData() {
    LinkedHashMap<String, List<String>> data = new LinkedHashMap<>();
    data.put("core_users", Arrays.asList("db/core_users/user1.json", "db/core_users/user2.json",
        "db/core_users/user3.json", "db/core_users/user4.json"));
    data.put("core_groups_higher_level",
        Arrays.asList("db/core_groups_higher_level/highlevelgroup.json",
            "db/core_groups_higher_level/highlevelgroup2.json",
            "db/core_groups_higher_level/highlevelgroup3.json"));
    data.put("core_groups_lower_level",
        Arrays.asList("db/core_groups_lower_level/lowerlevelgroup.json"));
    data.put("flow_task_templates",
        Arrays.asList("tests/setup/templates/1.json", "tests/setup/templates/2.json",
            "tests/setup/templates/3.json", "tests/setup/templates/4.json",
            "tests/setup/templates/5.json", "tests/setup/templates/6.json",
            "tests/setup/templates/7.json", "tests/setup/templates/8.json",
            "tests/setup/templates/9.json", "tests/setup/templates/10.json",
            "tests/setup/templates/11.json", "tests/setup/templates/12.json",
            "tests/setup/templates/13.json", "tests/setup/templates/14.json",
            "tests/setup/templates/15.json", "tests/setup/templates/16.json",
            "tests/setup/templates/17.json", "tests/setup/templates/18.json",
            "tests/setup/templates/19.json", "tests/setup/templates/20.json",
            "tests/setup/templates/21.json", "tests/setup/templates/23.json",
            "tests/setup/templates/24.json", "tests/setup/templates/26.json",
            "tests/setup/templates/27.json", "tests/setup/templates/28.json",
            "tests/setup/templates/29.json", "tests/setup/templates/31.json",
            "tests/setup/templates/32.json", "tests/setup/templates/33.json",
            "tests/setup/templates/34.json", "tests/setup/templates/35.json",
            "tests/setup/templates/36.json"));
    data.put("flow_teams", Arrays.asList("tests/setup/teams/team1.json"));
    data.put("flow_settings",
        Arrays.asList("db/flow_settings/setting1.json", "db/flow_settings/setting2.json",
            "db/flow_settings/setting3.json", "db/flow_settings/setting4.json",
            "db/flow_settings/setting5.json"));

    getTestCaseData(data);

    return data;
  }

  protected HttpEntity<FlowExecutionRequest> createHeaders(FlowExecutionRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + BEARER_TOKEN);
    headers.add("Content-type", "application/json");
    HttpEntity<FlowExecutionRequest> requestUpdate = new HttpEntity<>(request, headers);
    return requestUpdate;
  }

  protected HttpEntity<RequestFlowExecution> createInternalHeaders(RequestFlowExecution request) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + BEARER_TOKEN);
    headers.add("Content-type", "application/json");
    HttpEntity<RequestFlowExecution> requestUpdate = new HttpEntity<>(request, headers);
    return requestUpdate;
  }

  protected FlowActivity submitWorkflow(String workflowId) throws RestClientException {

    FlowExecutionRequest request = new FlowExecutionRequest();

    Map<String, String> inputs = new HashMap<String, String>();
    inputs.put("magic", "$(params.workflow-name)");
    request.setProperties(inputs);
    return this.submitWorkflow(workflowId, request);
  }

  protected void approveWorkflow(boolean status, String activityId) {
    try {

      ApprovalRequest request = new ApprovalRequest();
      request.setApproved(status);
      request.setComments("Test comment");
      request.setId(activityId);

      HttpHeaders headers = new HttpHeaders();
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + BEARER_TOKEN);
      headers.add("Content-type", "application/json");
      HttpEntity<ApprovalRequest> requestUpdate = new HttpEntity<>(request, headers);

      String url = "http://localhost:" + port + "/workflow/actions/action?id=" + activityId
          + "&approved=" + status;

      ResponseEntity<String> response =
          testRestTemplate.exchange(url, HttpMethod.PUT, requestUpdate, String.class);

    } catch (RestClientException e) {
    }
  }

  protected List<Action> getApprovals() {

    HttpHeaders headers = new HttpHeaders();
    headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + BEARER_TOKEN);
    headers.add("Content-type", "application/json");
    HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);

    final ResponseEntity<ListActionResponse> latestActivity =
        testRestTemplate.exchange("http://localhost:" + port + "/workflow/actions?status=submitted",
            HttpMethod.GET, requestUpdate, ListActionResponse.class);
    ListActionResponse approvalList = latestActivity.getBody();

    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String payload =
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(approvalList);
      LOGGER.info("Logging Approval Activity: ");
      LOGGER.info(payload);
    } catch (JsonProcessingException e) {
      LOGGER.error(ExceptionUtils.getStackTrace(e));
    }

    return approvalList.getRecords();
  }

  protected FlowActivity submitWorkflow(String workflowId, FlowExecutionRequest request)
      throws RestClientException {
    try {
      HttpEntity<FlowExecutionRequest> headers = createHeaders(request);
      ResponseEntity<String> response =
          testRestTemplate.exchange("http://localhost:" + port + "/workflow/execute/" + workflowId,
              HttpMethod.POST, headers, String.class);
      if (response.getStatusCode() == HttpStatus.OK) {
        ObjectMapper objectMapper = new ObjectMapper();
        FlowActivity activity = null;
        try {
          activity = objectMapper.readValue(response.getBody(), FlowActivity.class);
        } catch (Exception e) {
          e.printStackTrace();
        }
        logPayload(activity);
        return activity;
      }
    } catch (RestClientException e) {
      return null;
    }
    return null;
  }


  protected FlowWebhookResponse submitInternalWorkflow(String workflowId,
      RequestFlowExecution request) throws RestClientException {
    try {
      HttpEntity<RequestFlowExecution> headers = createInternalHeaders(request);
      ResponseEntity<String> response =
          testRestTemplate.exchange("http://localhost:" + port + "/internal/webhook/payload",
              HttpMethod.POST, headers, String.class);
      if (response.getStatusCode() == HttpStatus.OK) {
        ObjectMapper objectMapper = new ObjectMapper();
        FlowWebhookResponse activity = null;
        try {
          activity = objectMapper.readValue(response.getBody(), FlowWebhookResponse.class);
        } catch (Exception e) {
          e.printStackTrace();
        }

        return activity;
      }
    } catch (RestClientException e) {
      return null;
    }
    return null;
  }

  protected FlowActivity checkWorkflowActivity(String id) {

    HttpHeaders headers = new HttpHeaders();
    headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + BEARER_TOKEN);
    headers.add("Content-type", "application/json");
    HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);

    ResponseEntity<String> latestActivity =
        testRestTemplate.exchange("http://localhost:" + port + "/workflow/activity/" + id,
            HttpMethod.GET, requestUpdate, String.class);

    String str = latestActivity.getBody();
    System.out.println(str);

    ObjectMapper mapper = new ObjectMapper();

    FlowActivity finalActivity;
    try {
      finalActivity = mapper.readValue(str, FlowActivity.class);
      logPayload(finalActivity);
      return finalActivity;
    } catch (JsonProcessingException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return null;

  }

  private void logPayload(FlowActivity request) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
      LOGGER.info("Logging Flow Activity: ");
      LOGGER.info(payload);
    } catch (JsonProcessingException e) {
      LOGGER.error(ExceptionUtils.getStackTrace(e));
    }
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
    super.setUp();
    mockServer = MockRestServiceServer.bindTo(this.restTemplate).ignoreExpectOrder(true).build();
    mockServer
        .expect(times(1), requestTo(containsString("http://localhost:8084/internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));

    mockServer.expect(times(1), requestTo(containsString("controller/workflow/execute")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
    mockServer.expect(times(1), requestTo(containsString("controller/task/execute")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));

    mockServer.expect(times(1), requestTo(containsString("controller/workflow/terminate")))
        .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.OK));
  }

  protected abstract void getTestCaseData(Map<String, List<String>> data);

}
