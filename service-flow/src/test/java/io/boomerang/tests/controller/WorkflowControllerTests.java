package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.integrations.data.entity.RevisionEntity;
import io.boomerang.integrations.data.entity.WorkflowEntity;
import io.boomerang.misc.FlowTests;
import io.boomerang.v3.mongo.model.ActivityStorage;
import io.boomerang.v3.mongo.model.Storage;
import io.boomerang.v3.mongo.model.TaskConfigurationNode;
import io.boomerang.v3.mongo.model.TriggerEvent;
import io.boomerang.v3.mongo.model.TriggerScheduler;
import io.boomerang.v3.mongo.model.Triggers;
import io.boomerang.v3.mongo.model.WorkflowConfiguration;
import io.boomerang.v3.mongo.model.WorkflowScope;
import io.boomerang.v3.mongo.model.WorkflowStatus;
import io.boomerang.workflow.WebhookEventControllerV2;
import io.boomerang.workflow.WorkflowController;
import io.boomerang.workflow.model.FlowWorkflowRevision;
import io.boomerang.workflow.model.GenerateTokenResponse;
import io.boomerang.workflow.model.RevisionResponse;
import io.boomerang.workflow.model.WorkflowExport;
import io.boomerang.workflow.model.WorkflowShortSummary;
import io.boomerang.workflow.model.WorkflowSummary;
import io.boomerang.workflow.model.projectstormv5.RestConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class WorkflowControllerTests extends FlowTests {

  @Autowired private WorkflowController controller;

  @Autowired private WebhookEventControllerV2 internalController;

  @Test
  public void testInternalWorkflowListing() {
    List<WorkflowShortSummary> summaryList = internalController.getAllWorkflows();

    assertNotNull(summaryList);
    assertEquals(18, summaryList.size());
    assertNotNull(summaryList);
    assertEquals(18, summaryList.size());
  }

  @Test
  public void testGetWorkflowLatestVersion() {

    FlowWorkflowRevision entity = controller.getWorkflowLatestVersion("5d1a188af6ca2c00014c4314");

    assertEquals("5d1a188af6ca2c00014c4314", entity.getWorkFlowId());
  }

  @Test
  public void testGetWorkflowVersion() {
    FlowWorkflowRevision entity = controller.getWorkflowVersion("5d1a188af6ca2c00014c4314", 1L);
    assertEquals(1L, entity.getVersion());
    assertEquals("5d1a188af6ca2c00014c4314", entity.getWorkFlowId());
  }

  @Test
  public void testGetWorkflowWithId() {
    WorkflowSummary summary = controller.getWorkflowWithId("5d1a188af6ca2c00014c4314");
    assertEquals("5d1a188af6ca2c00014c4314", summary.getId());
    Optional<WorkflowProperty> passProp = summary.getProperties().stream()
        .filter(f -> FieldType.PASSWORD.value().equals(f.getType())).findAny();
    if (passProp.isPresent()) {
      assertNull(passProp.get().getDefaultValue());
    }
  }

  @Test
  public void testInsertWorkflow() {
    WorkflowSummary entity = new WorkflowSummary();
    entity.setName("TestWorkflow");
    entity.setFlowTeamId("5d1a1841f6ca2c00014c4309");
    entity.setStatus(WorkflowStatus.deleted);
    WorkflowSummary summary = controller.insertWorkflow(entity);
    assertEquals("TestWorkflow", summary.getName());
    assertEquals(WorkflowStatus.active, summary.getStatus());

  }

  @Test
  public void testinsertWorkflow() throws IOException {

    File resource = new ClassPathResource("json/updated-model-v5.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    FlowWorkflowRevision revision = objectMapper.readValue(json, FlowWorkflowRevision.class);
    revision.setMarkdown("test");

    FlowWorkflowRevision revisionEntity =
        controller.insertWorkflow("5d1a188af6ca2c00014c4314", revision);
    assertEquals(2L, revisionEntity.getVersion());
    assertEquals("test", revisionEntity.getMarkdown());
  }

  @Test
  public void testUpdateWorkflow() {
    WorkflowSummary entity = new WorkflowSummary();
    entity.setId("5d1a188af6ca2c00014c4314");
    entity.setName("TestUpdateWorkflow");
    entity.setStorage(new Storage());
    entity.getStorage().setActivity(new ActivityStorage());
    WorkflowSummary updatedEntity = controller.updateWorkflow(entity);
    assertEquals("5d1a188af6ca2c00014c4314", updatedEntity.getId());
    assertEquals("TestUpdateWorkflow", updatedEntity.getName());
  }

  @Test
  public void testUpdateWorkflowProperties() {

    WorkflowAbstractParam property = new WorkflowAbstractParam();
    property.setKey("testKey");
    property.setDescription("testDescription");
    property.setLabel("testLabel");
    property.setRequired(true);
    property.setType("testing");

    List<WorkflowAbstractParam> properties = new ArrayList<>();
    properties.add(property);

    WorkflowEntity entity =
        controller.updateWorkflowProperties("5d1a188af6ca2c00014c4314", properties);

    assertNotNull(entity.getProperties());
    assertEquals(1, entity.getProperties().size());
    assertEquals("testDescription", entity.getProperties().get(0).getDescription());

  }

  @Test
  public void testUpdateWorkflowPasswordProperty() {

    WorkflowProperty passProperty = new WorkflowProperty();
    passProperty.setKey("myPassword");
    passProperty.setDescription("testDescriptionPass");
    passProperty.setLabel("testLabelPass");
    passProperty.setRequired(false);
    passProperty.setType("password");
    passProperty.setDefaultValue("sensitiveData");

    List<WorkflowAbstractParam> properties = new ArrayList<>();
    properties.add(passProperty);

    WorkflowEntity entity =
        controller.updateWorkflowProperties("5d1a188af6ca2c00014c4314", properties);
    Optional<WorkflowProperty> passProp = entity.getProperties().stream()
        .filter(f -> FieldType.PASSWORD.value().equals(f.getType())).findAny();
    assertTrue(passProp.isPresent());
    assertNull(passProp.get().getDefaultValue());
    assertTrue(passProp.get().isHiddenValue());
  }


  @Test
  public void testExportWorkflow() {
    ResponseEntity<InputStreamResource> export =
        controller.exportWorkflow("5d1a188af6ca2c00014c4314");
    assertEquals(HttpStatus.OK, export.getStatusCode());
  }

  @Test
  public void testImportWorkflowUpdate() throws IOException {
    WorkflowExport export = new WorkflowExport();
    export.setDescription("testImportDescription");
    export.setName("testImportName");
    export.setId("5d7177af2c57250007e3d7a1");
    export.setStorage(new Storage());
    export.getStorage().setActivity(new ActivityStorage());
    TriggerEvent manual = new TriggerEvent();
    manual.setEnable(true);

    Triggers triggers = new Triggers();
    triggers.setManual(manual);

    export.setTriggers(triggers);

    List<TaskConfigurationNode> nodes = new ArrayList<>();

    WorkflowConfiguration config = new WorkflowConfiguration();
    config.setNodes(nodes);

    File resource = new ClassPathResource("json/json-sample.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper();
    RevisionEntity revision = objectMapper.readValue(json, RevisionEntity.class);

    export.setLatestRevision(revision);

    controller.importWorkflow(export, true, "", WorkflowScope.team);

    WorkflowSummary summary = controller.getWorkflowWithId("5d1a188af6ca2c00014c4314");
    assertEquals("test", summary.getDescription());
  }

  @Test
  public void testImportWorkflow() throws IOException {

    File resource = new ClassPathResource("scenarios/import/import-sample.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper();
    WorkflowExport importedWorkflow = objectMapper.readValue(json, WorkflowExport.class);
    controller.importWorkflow(importedWorkflow, false, "", WorkflowScope.team);
    assertTrue(true);
  }

  @Test
  public void testGenerateWebhookToken() {

    GenerateTokenResponse response = controller.createToken("5d1a188af6ca2c00014c4314", "Token");
    assertNotEquals("", response.getToken());
  }

  @Test
  public void testDeleteWorkflow() {
    controller.deleteWorkflowWithId("5d1a188af6ca2c00014c4314");
    assertEquals(WorkflowStatus.deleted,
        controller.getWorkflowWithId("5d1a188af6ca2c00014c4314").getStatus());
  }

  @Test
  public void testViewChangeLog() {
    List<RevisionResponse> response =
        controller.viewChangelog(getOptionalString("5d1a188af6ca2c00014c4314"),
            getOptionalOrder(Direction.ASC), getOptionalString("sort"), 0, 2147483647);
    assertEquals(1, response.size());
    assertEquals(1, response.get(0).getVersion());
  }

  @Test
  public void testUpdateWorkflowTriggers() {

    TriggerScheduler scheduler = new TriggerScheduler();
    scheduler.setEnable(true);

    TriggerEvent webhook = new TriggerEvent();
    webhook.setEnable(false);
    webhook.setToken("token");

    WorkflowSummary entity = controller.getWorkflowWithId("5d1a188af6ca2c00014c4314");
    assertNotNull(entity.getTriggers());
    assertNotNull(entity.getTriggers().getWebhook());
    assertEquals(false, entity.getTriggers().getScheduler().getEnable());
    assertEquals(true, entity.getTriggers().getWebhook().getEnable());
    assertEquals("A5DF2F840C0DFF496D516B4F75BD947C9BC44756A8AE8571FC45FCB064323641",
        entity.getTriggers().getWebhook().getToken());

    entity.getTriggers().setScheduler(scheduler);
    entity.getTriggers().setWebhook(webhook);

    WorkflowSummary updatedEntity = controller.updateWorkflow(entity);

    assertEquals("5d1a188af6ca2c00014c4314", updatedEntity.getId());

    assertEquals(true, updatedEntity.getTriggers().getScheduler().getEnable());
    assertEquals(false, updatedEntity.getTriggers().getWebhook().getEnable());
    assertEquals("token", updatedEntity.getTriggers().getWebhook().getToken());
  }

  @Test
  @Disabled
  public void testUpdateWorkflowTriggerNull() {

    WorkflowSummary entity = controller.getWorkflowWithId("5d1a188af6ca2c00014c4314");
    assertEquals(false, entity.getTriggers().getScheduler().getEnable());
    entity.setTriggers(null);
    assertNull(entity.getTriggers());

    WorkflowSummary updatedEntity = controller.updateWorkflow(entity);

    assertEquals("5d1a188af6ca2c00014c4314", updatedEntity.getId());
    assertEquals(false, updatedEntity.getTriggers().getScheduler().getEnable());

  }

  @Test
  @Disabled
  public void testUpdateWorkflowTriggerEvent() {}

  Optional<String> getOptionalString(String string) {
    return Optional.of(string);
  }

  Optional<Direction> getOptionalOrder(Direction direction) {
    return Optional.of(direction);
  }

  @Test
  public void testMissingTemplateVersionRevision() {

    FlowWorkflowRevision entity = controller.getWorkflowVersion("5d7177af2c57250007e3d7a1", 1l);
    assertNotNull(entity);
    verifyTemplateVersions(entity);
  }

  @Test
  public void testMissingTemplateVersionLatestRevision() {

    FlowWorkflowRevision entity = controller.getWorkflowLatestVersion("5d7177af2c57250007e3d7a1");
    assertNotNull(entity);
    verifyTemplateVersions(entity);
  }

  @Test
  public void testAvaliableParameters() {
    List<String> parameters = controller.getWorkflowParameters("5d1a188af6ca2c00014c4314");
    assertEquals(16, parameters.size());
    assertEquals("workflow.params.password", parameters.get(0));
    assertEquals("params.password", parameters.get(1));
    assertEquals("workflow.params.hello", parameters.get(2));
    assertEquals("params.hello", parameters.get(3));
    assertEquals("system.params.workflow-id", parameters.get(4));
    assertEquals("params.workflow-id", parameters.get(5));

  }

  private void verifyTemplateVersions(FlowWorkflowRevision entity) {
    RestConfig config = entity.getConfig();
    for (io.boomerang.model.projectstormv5.ConfigNodes taskNode : config.getNodes()) {
      if (taskNode.getTaskId() != null) {
        assertNotNull(taskNode.getTaskVersion());
      }
    }
  }
}
