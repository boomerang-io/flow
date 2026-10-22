package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** What a container task ran: its image, command, arguments, script and environment. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRunSpec(
    List<String> arguments,
    List<String> command,
    List<TaskEnvVar> envs,
    String image,
    String script,
    String workingDir,
    Boolean debug,
    Integer timeout,
    TaskDeletion deletion) {

  // The command, arguments, script and environment are left out: any may carry a secret.
  @Override
  public String toString() {
    return "TaskRunSpec[image=" + image + ", timeout=" + timeout + "]";
  }
}
