package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a container task runs, already resolved by the engine: every {@code $(params.x)} and
 * {@code $(tasks.t.results.r)} reference in the script, command, arguments and environment has
 * been substituted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRunSpec {

  private List<String> arguments;
  private List<String> command;
  private List<TaskEnvVar> envs;
  private String image;
  private String script;
  private String workingDir;
  private Boolean debug = false;
  private Integer timeout;
  private TaskDeletion deletion;

  public List<String> getArguments() {
    return arguments;
  }

  public void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public List<String> getCommand() {
    return command;
  }

  public void setCommand(List<String> command) {
    this.command = command;
  }

  public List<TaskEnvVar> getEnvs() {
    return envs;
  }

  public void setEnvs(List<TaskEnvVar> envs) {
    this.envs = envs;
  }

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public String getScript() {
    return script;
  }

  public void setScript(String script) {
    this.script = script;
  }

  public String getWorkingDir() {
    return workingDir;
  }

  public void setWorkingDir(String workingDir) {
    this.workingDir = workingDir;
  }

  public Boolean getDebug() {
    return debug;
  }

  public void setDebug(Boolean debug) {
    this.debug = debug;
  }

  public Integer getTimeout() {
    return timeout;
  }

  public void setTimeout(Integer timeout) {
    this.timeout = timeout;
  }

  /** The deletion policy, or null when the engine sent none this SDK knows. */
  public TaskDeletion getDeletion() {
    return deletion;
  }

  public void setDeletion(TaskDeletion deletion) {
    this.deletion = deletion;
  }

  @Override
  public String toString() {
    return "TaskRunSpec[image=" + image + ", command=" + command + ", deletion=" + deletion + "]";
  }
}
