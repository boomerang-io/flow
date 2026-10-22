package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.boomerang.misc.FlowTests;
import io.boomerang.v3.mongo.model.ChangeLog;
import io.boomerang.v3.mongo.model.FlowTaskTemplateStatus;
import io.boomerang.v3.mongo.model.Revision;
import io.boomerang.workflow.TaskControllerV2;
import io.boomerang.workflow.model.FlowTaskTemplate;
import io.boomerang.workflow.tekton.TektonTask;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class TaskTemplateControllerTests extends FlowTests {

  @Autowired private TaskControllerV2 controller;

  @Test
  public void testGetTaskTemplateWithId() {
    FlowTaskTemplate template = controller.getTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3");
    assertEquals("5bd9d0825a5df954ad5bb5c3", template.getId());
    assertEquals(1, template.getCurrentVersion());
    assertEquals(true, template.isVerified());
  }

  @Test
  public void testGetAllTaskTemplates() {
    List<FlowTaskTemplate> templates = controller.getAllTaskTemplates(null, null);
    assertEquals(6, templates.size());
    assertEquals(1, templates.get(0).getCurrentVersion());
    assertEquals(true, templates.get(0).isVerified());
    assertEquals(false, templates.get(1).isVerified());
  }

  @Test
  public void testInsertTaskTemplate() {
    FlowTaskTemplate entity = new FlowTaskTemplate();
    entity.setDescription("test");

    entity.setName("TestTaskTemplate");
    entity.setNodetype("custom");
    FlowTaskTemplate testTemplate = controller.insertTaskTemplate(entity);
    assertEquals("TestTaskTemplate", testTemplate.getName());
    assertEquals(false, testTemplate.isVerified());
  }

  @Test
  public void testUpdateTaskTemplate() {
    FlowTaskTemplate entity = new FlowTaskTemplate();

    entity.setId("5bd9d0825a5df954ad5bb5c3");
    entity.setDescription("test");
    entity.setName("TestTaskTemplate");

    Revision revision = new Revision();
    ChangeLog changelog = new ChangeLog();
    changelog.setReason("reason");

    revision.setChangelog(changelog);

    revision.setChangelog(changelog);

    List<Revision> revisions = new ArrayList<>();
    revisions.add(revision);

    entity.setRevisions(revisions);
    FlowTaskTemplate updatedEntity = controller.updateTaskTemplate(entity);
    assertEquals(updatedEntity.getId(), entity.getId());
    assertEquals("test", updatedEntity.getDescription());
    //  assertEquals(date, updatedEntity.getCreatedDate());
    assertEquals(1, updatedEntity.getCurrentVersion());

    assertNotNull(updatedEntity.getRevisions().get(0).getChangelog().getUserId());
    assertEquals("reason", updatedEntity.getRevisions().get(0).getChangelog().getReason());
    assertEquals(true, updatedEntity.isVerified());
  }

  @Test
  public void testDeleteTaskTemplate() {
    controller.deleteTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3");
    assertNotNull(controller.getTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3"));
    assertEquals(
        FlowTaskTemplateStatus.inactive,
        controller.getTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3").getStatus());

    controller.activateTaskTemplate("5bd9d0825a5df954ad5bb5c3");
    assertNotNull(controller.getTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3"));
    assertEquals(
        FlowTaskTemplateStatus.active,
        controller.getTaskTemplateWithId("5bd9d0825a5df954ad5bb5c3").getStatus());
  }

  @Test
  public void testLatestTaskYaml() {
    String templateId = "5bd9d0825a5df954ad5bb5c3";

    TektonTask task = this.controller.getTaskTemplateYamlWithId(templateId);
    assertNotNull(task);
    assertNotNull(task.getSpec());
    assertNotNull(task.getSpec().getParams());
  }

  @Test
  public void testLatestTaskYamlWithRevision() {
    String templateId = "5bd9d0825a5df954ad5bb5c3";

    TektonTask task = this.controller.getTaskTemplateYamlWithIdandRevision(templateId, 1);
    assertNotNull(task.getSpec().getParams());
  }
}
