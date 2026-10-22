//package io.boomerang.tests.service;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertNotNull;
//import static org.junit.Assert.assertNull;
//import static org.junit.Assert.assertTrue;
//import static org.mockito.Mockito.mock;
//
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.Map;
//
//import javax.servlet.http.HttpServletResponse;
//
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.mockito.Mockito;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//import org.springframework.mock.web.MockHttpServletResponse;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
//import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
//import io.boomerang.kube.exception.KubeRuntimeException;
//import io.boomerang.model.Response;
//import io.boomerang.model.Storage;
//import io.boomerang.model.Task;
//import io.boomerang.model.TaskResponse;
//import io.boomerang.model.Workflow;
//import io.boomerang.tests.kube.FlowKubeServiceImpl;
//import io.kubernetes.client.ApiException;
//import io.kubernetes.client.models.V1ConfigMap;
//import io.kubernetes.client.models.V1Job;
//import io.kubernetes.client.models.V1PersistentVolumeClaim;
//import io.kubernetes.client.models.V1PersistentVolumeClaimStatus;
//import io.kubernetes.client.models.V1Status;
//
//@RunWith(SpringJUnit4ClassRunner.class)
//@SpringBootTest
//@ActiveProfiles("live")
//public class FlowControllerServiceTest {
//
//  @Autowired
//  private FlowControllerServiceImpl flowControllerService;
//
//  @MockBean
//  private FlowKubeServiceImpl kubeService;
//
//  @Test
//  public void testCreateWorkflow() throws ApiException {
//    Workflow workflow = getDefaultWorkflow();
//    Storage storage = new Storage();
//    storage.setEnable(true);
//    storage.setSize("200");
//    workflow.setWorkflowStorage(storage);
//
//    V1PersistentVolumeClaim claim = new V1PersistentVolumeClaim();
//    V1PersistentVolumeClaimStatus status = new V1PersistentVolumeClaimStatus();
//    status.setPhase("phase1");
//    Mockito.when(kubeService.createPVC("workflowName", "workflowId", "workflowActivityId", "200"))
//        .thenReturn(claim);
//    Mockito.when(kubeService.watchPVC("workflowId", "workflowActivityId")).thenReturn(status);
//
//    Mockito.when(kubeService.createWorkflowConfigMap("workflowName", "workflowId",
//        "workflowActivityId", null)).thenReturn(new V1ConfigMap());
//    Mockito.when(kubeService.watchConfigMap("workflowId", "workflowActivityId", null))
//        .thenReturn(new V1ConfigMap());
//
//    Response response = flowControllerService.createWorkflow(workflow);
//    assertEquals("0", response.getCode());
//    assertTrue(response.getMessage().startsWith("Workflow Activity (workflowActivityId"));
//
//    Mockito.verify(kubeService).createPVC("workflowName", "workflowId", "workflowActivityId",
//        "200");
//    Mockito.verify(kubeService).watchPVC("workflowId", "workflowActivityId");
//    Mockito.verify(kubeService).createWorkflowConfigMap("workflowName", "workflowId",
//        "workflowActivityId", null);
//    Mockito.verify(kubeService).watchConfigMap("workflowId", "workflowActivityId", null);
//  }
//
//  @Test
//  public void testCreateWorkflowWithException() throws ApiException {
//    Workflow workflow = getDefaultWorkflow();
//    Storage storage = new Storage();
//    storage.setEnable(false);
//    storage.setSize("200");
//    workflow.setWorkflowStorage(storage);
//    workflow.setProperties(new HashMap<>());
//    
//    Mockito.when(kubeService.createWorkflowConfigMap("workflowName", "workflowId",
//        "workflowActivityId", workflow.getProperties())).thenThrow(KubeRuntimeException.class);
//
//    Response response = flowControllerService.createWorkflow(workflow);
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//
//    Mockito.verify(kubeService).createWorkflowConfigMap("workflowName", "workflowId",
//        "workflowActivityId", workflow.getProperties());
//  }
//
//  @Test
//  public void testTerminateWorkflow() {
//    Workflow workflow = getDefaultWorkflow();
//    Mockito.when(kubeService.deletePVC(workflow.getWorkflowId(), workflow.getWorkflowActivityId()))
//        .thenReturn(new V1Status());
//    Mockito.when(kubeService.deleteConfigMap(workflow.getWorkflowId(),
//        workflow.getWorkflowActivityId(), null)).thenReturn(new V1Status());
//
//    Response response = flowControllerService.terminateWorkflow(workflow);
//    assertEquals("0", response.getCode());
//    assertTrue(response.getMessage().startsWith("Workflow Activity (workflowActivityId"));
//
//    Mockito.verify(kubeService).deletePVC(workflow.getWorkflowId(),
//        workflow.getWorkflowActivityId());
//    Mockito.verify(kubeService).deleteConfigMap(workflow.getWorkflowId(),
//        workflow.getWorkflowActivityId(), null);
//  }
//
//  @Test
//  public void testTerminateWorkflowWithException() throws ApiException {
//    Workflow workflow = getDefaultWorkflow();
//    Mockito.when(kubeService.deletePVC(workflow.getWorkflowId(), workflow.getWorkflowActivityId()))
//        .thenThrow(KubeRuntimeException.class);
//
//    Response response = flowControllerService.terminateWorkflow(workflow);
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//
//    Mockito.verify(kubeService).deletePVC(workflow.getWorkflowId(),
//        workflow.getWorkflowActivityId());
//  }
//
//  @Test
//  public void testExecuteTask() {
//    Task task = getDefaultTask();
//
//    Mockito.when(kubeService.createTaskConfigMap(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskName(), task.getTaskId(),
//        task.getProperties())).thenReturn(new V1ConfigMap());
//    Mockito.when(kubeService.watchConfigMap(null, task.getWorkflowActivityId(), task.getTaskId()))
//        .thenReturn(new V1ConfigMap());
//    Mockito.when(kubeService.createJob(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(),task.getTaskActivityId(), task.getTaskName(), task.getTaskId(), task.getArguments(),
//        task.getProperties())).thenReturn(new V1Job());
//    Mockito.when(
//        kubeService.watchJob(task.getWorkflowId(), task.getWorkflowActivityId(), task.getTaskId()))
//        .thenReturn(new V1Job());
//
//    Mockito
//        .when(kubeService.getTaskOutPutConfigMapData(task.getWorkflowId(),
//            task.getWorkflowActivityId(), task.getTaskId(), task.getTaskName()))
//        .thenReturn(new HashMap<>());
//    Mockito.when(kubeService.deleteConfigMap(null, task.getWorkflowActivityId(), task.getTaskId()))
//        .thenReturn(new V1Status());
//
//    TaskResponse response = flowControllerService.executeTask(task);
//    assertEquals("0", response.getCode());
//    assertTrue(response.getMessage().startsWith("Task (taskId) has been executed successfully."));
//    assertNotNull(response.getOutput());
//
//    Mockito.verify(kubeService).createTaskConfigMap(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskName(), task.getTaskId(),
//        task.getProperties());
//    Mockito.verify(kubeService).watchConfigMap(null, task.getWorkflowActivityId(),
//        task.getTaskId());
//    Mockito.verify(kubeService).createJob(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskActivityId(),task.getTaskName(), task.getTaskId(), task.getArguments(),
//        task.getProperties());
//    Mockito.verify(kubeService).watchJob(task.getWorkflowId(), task.getWorkflowActivityId(),
//        task.getTaskId());
//
//    Mockito.verify(kubeService).getTaskOutPutConfigMapData(task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskId(), task.getTaskName());
//    Mockito.verify(kubeService).deleteConfigMap(null, task.getWorkflowActivityId(),
//        task.getTaskId());
//  }
//
//  @Test
//  public void testExecuteTaskWithException() {
//    Task task = getDefaultTask();
//    task.setArguments(new ArrayList<>());
//
//    Mockito.when(kubeService.createTaskConfigMap(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskName(), task.getTaskId(),
//        task.getProperties())).thenThrow(KubeRuntimeException.class);
//    Mockito
//        .when(kubeService.getTaskOutPutConfigMapData(task.getWorkflowId(),
//            task.getWorkflowActivityId(), task.getTaskId(), task.getTaskName()))
//        .thenReturn(new HashMap<>());
//    Mockito.when(kubeService.deleteConfigMap(null, task.getWorkflowActivityId(), task.getTaskId()))
//        .thenReturn(new V1Status());
//
//    TaskResponse response = flowControllerService.executeTask(task);
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//    assertNotNull(response.getOutput());
//
//    Mockito.verify(kubeService).createTaskConfigMap(task.getWorkflowName(), task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskName(), task.getTaskId(),
//        task.getProperties());
//    Mockito.verify(kubeService).getTaskOutPutConfigMapData(task.getWorkflowId(),
//        task.getWorkflowActivityId(), task.getTaskId(), task.getTaskName());
//    Mockito.verify(kubeService).deleteConfigMap(null, task.getWorkflowActivityId(),
//        task.getTaskId());
//  }
//
//  @Test
//  public void testSetJobOutputProperty() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    String taskName = "taskName";
//    Map<String, String> properties = new HashMap<>();
//    properties.put("key", "value");
//    Mockito.doNothing().when(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId,
//        taskName, properties);
//
//    Response response = flowControllerService.setTaskResultParameter(workflowId, workflowActivityId,
//        taskId, taskName, "key", "value");
//    assertEquals("0", response.getCode());
//    assertTrue(response.getMessage().startsWith(
//        "Property has been set against workflow (workflowActivityId) and task (taskId)"));
//
//    Mockito.verify(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId, taskName,
//        properties);
//  }
//
//  @Test
//  public void testSetJobOutputPropertyWithException() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    String taskName = "taskName";
//    Map<String, String> properties = new HashMap<>();
//    properties.put("key", "value");
//    Mockito.doThrow(KubeRuntimeException.class).when(kubeService).patchTaskConfigMap(workflowId,
//        workflowActivityId, taskId, taskName, properties);
//
//    Response response = flowControllerService.setTaskResultParameter(workflowId, workflowActivityId,
//        taskId, taskName, "key", "value");
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//
//    Mockito.verify(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId, taskName,
//        properties);
//  }
//
//  @Test
//  public void testSetJobOutputProperties() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    String taskName = "taskName";
//    Map<String, String> properties = new HashMap<>();
//    properties.put("key", "value");
//    Mockito.doNothing().when(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId,
//        taskName, properties);
//
//    Response response = flowControllerService.setTaskResultParameters(workflowId, workflowActivityId,
//        taskId, taskName, properties);
//    assertEquals("0", response.getCode());
//    assertTrue(response.getMessage().startsWith(
//        "Properties have been set against workflow (workflowActivityId) and task (taskId)"));
//
//    Mockito.verify(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId, taskName,
//        properties);
//  }
//
//  @Test
//  public void testSetJobOutputPropertiesWithException() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    String taskName = "taskName";
//    Map<String, String> properties = new HashMap<>();
//    properties.put("key", "value");
//    Mockito.doThrow(KubeRuntimeException.class).when(kubeService).patchTaskConfigMap(workflowId,
//        workflowActivityId, taskId, taskName, properties);
//
//    Response response = flowControllerService.setTaskResultParameters(workflowId, workflowActivityId,
//        taskId, taskName, properties);
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//
//    Mockito.verify(kubeService).patchTaskConfigMap(workflowId, workflowActivityId, taskId, taskName,
//        properties);
//  }
//
//  @Test
//  public void testGetLogForTask() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//
//    Mockito.when(kubeService.getPodLog(workflowId, workflowActivityId, taskId,taskId))
//        .thenReturn("Success");
//
//    Response response = flowControllerService.getLogForTask(workflowId, workflowActivityId, taskId,taskId);
//    assertEquals("0", response.getCode());
//    assertEquals("Success", response.getMessage());
//
//    Mockito.verify(kubeService).getPodLog(workflowId, workflowActivityId, taskId,taskId);
//  }
//
//  @Test
//  public void testGetLogForTaskWithException() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//
//    Mockito.when(kubeService.getPodLog(workflowId, workflowActivityId, taskId,taskId))
//        .thenThrow(KubeRuntimeException.class);
//
//    Response response = flowControllerService.getLogForTask(workflowId, workflowActivityId, taskId,taskId);
//    assertEquals("1", response.getCode());
//    assertTrue(response.getMessage()
//        .startsWith("net.boomerangplatform.kube.exception.KubeRuntimeException"));
//
//    Mockito.verify(kubeService).getPodLog(workflowId, workflowActivityId, taskId,taskId);
//  }
//
//  @Test
//  public void testStreamLogForTask() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    HttpServletResponse response = new MockHttpServletResponse();
//
//    Mockito.when(kubeService.streamPodLog(response, workflowId, workflowActivityId, taskId,taskId))
//        .thenReturn(mock(StreamingResponseBody.class));
//
//    StreamingResponseBody streamingResponseBody =
//        flowControllerService.streamLogForTask(response, workflowId, workflowActivityId, taskId,taskId);
//    assertNotNull(streamingResponseBody);
//
//    Mockito.verify(kubeService).streamPodLog(response, workflowId, workflowActivityId, taskId,taskId);
//  }
//
//  @Test
//  public void testStreamLogForTaskWithException() {
//    String workflowId = "workflowId";
//    String workflowActivityId = "workflowActivityId";
//    String taskId = "taskId";
//    HttpServletResponse response = new MockHttpServletResponse();
//
//    Mockito.when(kubeService.streamPodLog(response, workflowId, workflowActivityId, taskId,taskId))
//        .thenThrow(KubeRuntimeException.class);
//
//    StreamingResponseBody streamingResponseBody =
//        flowControllerService.streamLogForTask(response, workflowId, workflowActivityId, taskId,taskId);
//    assertNull(streamingResponseBody);
//
//    Mockito.verify(kubeService).streamPodLog(response, workflowId, workflowActivityId, taskId,taskId);
//  }
//
//  private Task getDefaultTask() {
//    Task task = new Task();
//    task.setWorkflowName("workflowName");
//    task.setWorkflowId("workflowId");
//    task.setWorkflowActivityId("workflowActivityId");
//    task.setTaskId("taskId");
//    task.setTaskName("taskName");
//    task.setProperties(new HashMap<>());
//    task.setProperty("name1", "value1");
//    return task;
//  }
//
//  private Workflow getDefaultWorkflow() {
//    Workflow workflow = new Workflow();
//    workflow.setWorkflowActivityId("workflowActivityId");
//    workflow.setWorkflowName("workflowName");
//    workflow.setWorkflowId("workflowId");
//    return workflow;
//  }
//
//}
