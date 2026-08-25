package io.boomerang.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.engine.TaskRunService;
import io.boomerang.workflow.WorkflowRunService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the one routing subtlety created by consolidating the v1 dispatcher protocol onto a single
 * path root: {@code GET /api/v1/dispatcher/{id}/workflows} (queue poll) and {@code PUT
 * /api/v1/dispatcher/workflowrun/{id}/start} (lifecycle callback) overlap in shape, so a dispatcher
 * whose registered id happens to be the literal string {@code workflowrun} or {@code taskrun} sits
 * exactly on the seam.
 *
 * <p>Spring's {@code RequestMappingInfo} ordering prefers a literal segment over a path variable
 * and the two mappings differ in segment count besides, so the queue polls must still win. This test
 * asserts the outcome rather than the reasoning — if it ever fails, the fix is to report it, NOT to
 * rename the {@code workflowrun}/{@code taskrun} segments.
 *
 * <p>Standalone MockMvc (no Spring context, no auth filter) is deliberate: what is under test is
 * handler-method selection alone.
 */
class DispatcherRouteCollisionTest {

  private DispatcherService dispatcherService;
  private WorkflowRunService workflowRunService;
  private TaskRunService taskRunService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    dispatcherService = mock(DispatcherService.class);
    workflowRunService = mock(WorkflowRunService.class);
    taskRunService = mock(TaskRunService.class);
    when(dispatcherService.getWorkflowQueue(any()))
        .thenReturn(ResponseEntity.ok(List.<WorkflowRun>of()));
    when(dispatcherService.getTaskQueue(any())).thenReturn(ResponseEntity.ok(List.<TaskRun>of()));
    when(workflowRunService.start(any(), any())).thenReturn(new WorkflowRun());
    when(taskRunService.start(any(), any())).thenReturn(ResponseEntity.ok(new TaskRun()));
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new DispatcherControllerV1(dispatcherService, workflowRunService, taskRunService))
            .build();
  }

  @Test
  void dispatcherIdOfWorkflowrunStillRoutesToTheQueueEndpoints() throws Exception {
    mockMvc.perform(get("/api/v1/dispatcher/workflowrun/workflows")).andExpect(status().isOk());
    verify(dispatcherService).getWorkflowQueue(eq("workflowrun"));

    mockMvc.perform(get("/api/v1/dispatcher/workflowrun/tasks")).andExpect(status().isOk());
    verify(dispatcherService).getTaskQueue(eq("workflowrun"));

    verify(workflowRunService, never()).start(any(), any());
    verify(taskRunService, never()).start(any(), any());
  }

  @Test
  void dispatcherIdOfTaskrunStillRoutesToTheQueueEndpoints() throws Exception {
    mockMvc.perform(get("/api/v1/dispatcher/taskrun/tasks")).andExpect(status().isOk());
    verify(dispatcherService).getTaskQueue(eq("taskrun"));

    mockMvc.perform(get("/api/v1/dispatcher/taskrun/workflows")).andExpect(status().isOk());
    verify(dispatcherService).getWorkflowQueue(eq("taskrun"));

    verify(workflowRunService, never()).start(any(), any());
    verify(taskRunService, never()).start(any(), any());
  }

  @Test
  void callbackPathsStillReachTheirOwnHandlers() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/dispatcher/workflowrun/run-1/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    verify(workflowRunService).start(eq("run-1"), any());

    mockMvc
        .perform(
            put("/api/v1/dispatcher/taskrun/run-2/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    verify(taskRunService).start(eq("run-2"), any());

    verify(dispatcherService, never()).getWorkflowQueue(any());
    verify(dispatcherService, never()).getTaskQueue(any());
  }
}
