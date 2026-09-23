package io.boomerang.dispatcher;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * A Task's own log, read from whatever runtime ran it - a Pod on Kubernetes, a container on a
 * Docker host. Exactly one implementation is active per deployment, chosen by {@code
 * dispatcher.executor}; it serves the {@code default} logging type, while loki reads from
 * elsewhere.
 */
public interface TaskLogStore {

  /** The whole log of a finished Task. */
  String get(String workflowRef, String workflowRunRef, String taskRunRef);

  /** The log as it is written, ending when the Task's runtime object goes terminal. */
  StreamingResponseBody stream(
      HttpServletResponse response, String workflowRef, String workflowRunRef, String taskRunRef);
}
