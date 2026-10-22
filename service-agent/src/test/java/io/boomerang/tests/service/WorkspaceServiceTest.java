package io.boomerang.tests.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import io.boomerang.kube.KubeServiceImpl;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@SpringBootTest
@ActiveProfiles({"local"})
@RunWith(SpringRunner.class)
@EnableKubernetesMockClient(crud = true)
public class WorkspaceServiceTest {

  private static final Logger LOGGER = LogManager.getLogger(WorkspaceServiceTest.class);

  KubernetesClient client = new DefaultKubernetesClient();

  @Autowired private KubeServiceImpl kubeService;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
  }

  @Test
  public void testCreateTaskConfigMap() {

    Map<String, String> labels = new HashMap<>();
    Map<String, String> params = new HashMap<>();
    params.put("test-param", "This is a test");
    kubeService.createTaskConfigMap(
        "test-cm-workflow",
        "20210926",
        "202109260627",
        "Test Task",
        "1234",
        "2021092606271234",
        labels,
        params);

    ConfigMapList configMapList = client.configMaps().inAnyNamespace().list();
    LOGGER.info("testCreateTaskConfigMap() - " + configMapList.toString());
    assertNotNull(configMapList);
    assertEquals(1, configMapList.getItems().size());
  }

  @Test
  public void testCreateTaskConfigMapWithEmptyParams() {

    Map<String, String> labels = new HashMap<>();
    Map<String, String> params = new HashMap<>();
    //    params.put("test-param", "This is a test");
    kubeService.createTaskConfigMap(
        "test-cm-workflow",
        "20210926",
        "202109260627",
        "Test Task",
        "1234",
        "2021092606271234",
        labels,
        params);

    ConfigMapList configMapList = client.configMaps().inAnyNamespace().list();
    LOGGER.info("testCreateTaskConfigMapWithEmptyParams() - " + configMapList.toString());
    assertNotNull(configMapList);
    assertEquals(1, configMapList.getItems().size());
  }
}
