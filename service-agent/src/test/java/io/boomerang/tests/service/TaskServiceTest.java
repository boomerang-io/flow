package io.boomerang.tests.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.kube.KubeServiceImpl;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles({"local"})
@EnableKubernetesMockClient(crud = true)
public class TaskServiceTest {

  private static final Logger LOGGER = LogManager.getLogger(TaskServiceTest.class);

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
  }

  @Test
  public void testCreateTaskConfigMap() {

    Map<String, String> labels = new HashMap<>();
    List<RunParam> params = List.of(new RunParam("test-param", "This is a test"));
    kubeService.createTaskConfigMap(
        "test-cm-workflow", "20210926", "Test Task", "2021092606271234", labels, params);

    ConfigMapList configMapList = client.configMaps().inAnyNamespace().list();
    LOGGER.info("testCreateTaskConfigMap() - " + configMapList.toString());
    assertNotNull(configMapList);
    assertEquals(1, configMapList.getItems().size());
  }

  @Test
  public void testCreateTaskConfigMapWithEmptyParams() {

    Map<String, String> labels = new HashMap<>();
    List<RunParam> params = List.of();
    kubeService.createTaskConfigMap(
        "test-cm-workflow", "20210926", "Test Task", "2021092606271234", labels, params);

    ConfigMapList configMapList = client.configMaps().inAnyNamespace().list();
    LOGGER.info("testCreateTaskConfigMapWithEmptyParams() - " + configMapList.toString());
    assertNotNull(configMapList);
    assertEquals(1, configMapList.getItems().size());
  }
}
