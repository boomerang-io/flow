package io.boomerang.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesCmd;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import io.boomerang.dispatcher.model.HeldWorkspace;
import io.boomerang.kube.KubeHelperService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the one thing the reconciler depends on: a Docker volume carries exactly the labels a
 * PersistentVolumeClaim carries, so the same release path finds and deletes it.
 */
class DockerWorkspaceStoreTest {

  private final DockerClient client = mock(DockerClient.class);

  private final KubeHelperService helperKubeService = new KubeHelperService();

  private DockerWorkspaceStore store;

  private ListVolumesCmd listCmd;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(helperKubeService, "bmrgProduct", "bmrg-flow");
    ReflectionTestUtils.setField(helperKubeService, "bmrgInstance", "bmrg-flow");
    store = new DockerWorkspaceStore(client, helperKubeService);

    listCmd = mock(ListVolumesCmd.class);
    when(client.listVolumesCmd()).thenReturn(listCmd);
    when(listCmd.withFilter(anyString(), any())).thenReturn(listCmd);
    volumesAre();
  }

  // The response mock is built and stubbed before the listCmd stubbing starts: Mockito treats a
  // when() evaluated inside another when()'s argument as an unfinished stubbing.
  private void volumesAre(InspectVolumeResponse... volumes) {
    ListVolumesResponse response = mock(ListVolumesResponse.class);
    when(response.getVolumes()).thenReturn(List.of(volumes));
    when(listCmd.exec()).thenReturn(response);
  }

  private static InspectVolumeResponse volume(String name, Map<String, String> labels) {
    InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
    when(volume.getName()).thenReturn(name);
    when(volume.getLabels()).thenReturn(labels);
    return volume;
  }

  @Test
  void aCreatedVolumeCarriesTheSameLabelsAsAClaim() {
    CreateVolumeCmd createCmd = mock(CreateVolumeCmd.class);
    when(client.createVolumeCmd()).thenReturn(createCmd);
    when(createCmd.withName(anyString())).thenReturn(createCmd);
    when(createCmd.withLabels(any())).thenReturn(createCmd);

    store.create("wf-1", "wf-1", "workflow", Map.of("team", "core"), "1Gi", "fast", "ReadWriteMany", 30L);

    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    verify(createCmd).withName(name.capture());
    assertEquals("bmrg-flow-vol-ws-workflow-wf-1", name.getValue());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
    verify(createCmd).withLabels(labels.capture());
    assertEquals("workspace", labels.getValue().get("boomerang.io/tier"));
    assertEquals("wf-1", labels.getValue().get("boomerang.io/workspace-ref"));
    assertEquals("workflow", labels.getValue().get("boomerang.io/workspace-type"));
    assertEquals("bmrg-flow", labels.getValue().get("boomerang.io/product"));
    assertEquals("core", labels.getValue().get("team"), "authored labels survive");
  }

  @Test
  void anExistingVolumeIsNotCreatedTwice() {
    volumesAre(volume("bmrg-flow-vol-ws-workflow-wf-1", Map.of()));

    store.create("wf-1", "wf-1", "workflow", null, "1Gi", "", "ReadWriteMany", 30L);

    verify(client, never()).createVolumeCmd();
    assertTrue(store.exists("wf-1", "workflow"));
  }

  @Test
  void heldReadsRefAndTypeBackFromTheVolumeLabels() {
    volumesAre(
        volume(
            "bmrg-flow-vol-ws-workflowrun-run-1",
            Map.of(
                "boomerang.io/workspace-ref", "run-1",
                "boomerang.io/workspace-type", "workflowrun")),
        volume("stray", Map.of()));

    assertEquals(
        List.of(new HeldWorkspace("run-1", "workflowrun"), new HeldWorkspace(null, null)),
        store.held());
  }

  @Test
  void deleteRemovesEveryVolumeMatchingTheLabels() {
    RemoveVolumeCmd removeCmd = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd(anyString())).thenReturn(removeCmd);
    volumesAre(volume("vol-a", Map.of()), volume("vol-b", Map.of()));

    store.delete("run-1", "workflowrun");

    verify(client).removeVolumeCmd("vol-a");
    verify(client).removeVolumeCmd("vol-b");
  }

  @Test
  void anAbsentWorkspaceDoesNotExist() {
    assertFalse(store.exists("run-1", "workflowrun"));
    assertFalse(store.exists(null, "workflowrun"));
  }
}
