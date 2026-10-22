package io.boomerang.agent;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/logs")
public class LogV1Controller {

  @Autowired private LogService logService;

  @GetMapping(value = "/stream")
  public ResponseEntity<StreamingResponseBody> streamLogForTask(
      HttpServletResponse response,
      @RequestParam(value = "workflowRef", required = true) String workflowRef,
      @RequestParam(value = "workflowRunRef", required = true) String workflowRunRef,
      @RequestParam(value = "taskRunRef", required = false) String taskRunRef) {
    return new ResponseEntity<>(
        logService.streamLogForTask(response, workflowRef, workflowRunRef, taskRunRef),
        HttpStatus.OK);
  }

  @GetMapping(value = "")
  public ResponseEntity<String> getLogForTask(
      @RequestParam(value = "workflowRef", required = true) String workflowRef,
      @RequestParam(value = "workflowActivityId", required = true) String workflowActivityId,
      @RequestParam(value = "workflowRunRef", required = false) String workflowRunRef,
      @RequestParam(value = "taskRunRef", required = true) String taskRunRef) {
    return new ResponseEntity<>(
        logService.getLogForTask(workflowRef, workflowRunRef, taskRunRef), HttpStatus.OK);
  }
}
