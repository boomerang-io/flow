package io.boomerang.client;

import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.error.BoomerangException;
import java.net.URI;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Primary
public class WorkflowClient {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.workflow.createschedule.url}")
  private String workflowCreateScheduleURL;

  @Value("${flow.workflow.submit.url}")
  private String workflowSubmitURL;

  @Value("${flow.workflow.relationship.url}")
  private String workflowRelationshipURL;

  @Autowired
  @Qualifier("insecureRestTemplate")
  public RestTemplate restTemplate;

  public WorkflowSchedule createSchedule(WorkflowSchedule workflowSchedule) {
    try {
      LOGGER.info("URL: " + workflowCreateScheduleURL);

      ResponseEntity<WorkflowSchedule> response =
          restTemplate.postForEntity(
              workflowCreateScheduleURL, workflowSchedule, WorkflowSchedule.class);

      LOGGER.info("Status Response: " + response.getStatusCode());
      LOGGER.info("Content Response: " + response.getBody().toString());

      return response.getBody();
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
      throw new BoomerangException(
          ex,
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          ex.getClass().getSimpleName(),
          "Exception in communicating with internal services.",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public WorkflowRun submitWorkflow(
      String ref, WorkflowRunRequest request, Optional<Boolean> start) {
    try {
      UriComponentsBuilder urlBuilder =
          UriComponentsBuilder.fromHttpUrl(workflowSubmitURL.replace("{ref}", ref));
      if (start.isPresent()) {
        urlBuilder.queryParam("start", start.get());
      }
      URI encodedURI = urlBuilder.build().encode().toUri();
      LOGGER.info("URL: " + encodedURI);

      ResponseEntity<WorkflowRun> response =
          restTemplate.postForEntity(encodedURI, request, WorkflowRun.class);

      LOGGER.info("Status Response: " + response.getStatusCode());
      LOGGER.info("Content Response: " + response.getBody().toString());

      return response.getBody();
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
      throw new BoomerangException(
          ex,
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          ex.getClass().getSimpleName(),
          "Exception in communicating with internal services.",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void createWorkflowRunRelationship(String workflow, String run) {
    try {
      String url = workflowRelationshipURL.replace("{workflow}", workflow).replace("{run}", run);
      LOGGER.debug("URL: " + url);

      ResponseEntity<Void> response = restTemplate.postForEntity(url, "", Void.class);

      LOGGER.debug("Status Response: " + response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
      throw new BoomerangException(
          ex,
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          ex.getClass().getSimpleName(),
          "Exception in communicating with internal services.",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
