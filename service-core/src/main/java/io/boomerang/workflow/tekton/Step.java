package io.boomerang.workflow.tekton;

import java.util.List;
import io.boomerang.common.model.TaskEnvVar;

public class Step {

  private String name;
  private String image;
  private String script;
  private String workingDir;
  private List<TaskEnvVar> env;
  private List<String> command;
  private List<String> args;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public List<String> getArgs() {
    return args;
  }

  public void setArgs(List<String> args) {
    this.args = args;
  }

  public List<String> getCommand() {
    return command;
  }

  public void setCommand(List<String> command) {
    this.command = command;
  }

  public String getScript() {
    return script;
  }

  public void setScript(String script) {
    this.script = script;
  }

  public List<TaskEnvVar> getEnv() {
    return env;
  }

  public void setEnv(List<TaskEnvVar> env) {
    this.env = env;
  }

  public String getWorkingDir() {
    return workingDir;
  }

  public void setWorkingDir(String workingDir) {
    this.workingDir = workingDir;
  }
}
