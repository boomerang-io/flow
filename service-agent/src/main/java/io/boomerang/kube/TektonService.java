package io.boomerang.kube;

import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskWorkspace;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

public interface TektonService {

  void cancelTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels);

  TaskRun createTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      String taskName,
      Map<String, String> customLabels,
      String image,
      List<String> command,
      String script,
      List<String> arguments,
      List<RunParam> parameters,
      List<TaskEnvVar> envVars,
      List<RunResult> results,
      String workingDir,
      List<TaskWorkspace> workspaces,
      long waitSeconds,
      Long timeout,
      Boolean debug)
      throws InterruptedException, ParseException;

  List<RunResult> watchTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels,
      Long timeout)
      throws InterruptedException;

  void deleteTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels);
}
