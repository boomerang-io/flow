package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A workflow param's type is a field type. Save refuses one that names no field type; a param
 * with no type, or one stored before save refused it, runs as a string, so no stored workflow
 * makes a submit fail.
 */
class WorkflowParamTypeTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void aTypeThatNamesNoFieldTypeIsRefusedAtSave() {
    Workflow workflow = workflow("param-type-unknown", declared("region", "string"));

    assertThatThrownBy(() -> workflowService.create(WORKSPACE, workflow))
        .isInstanceOf(BoomerangException.class)
        .satisfies(
            ex -> {
              BoomerangException refused = (BoomerangException) ex;
              assertThat(refused.getReason())
                  .isEqualTo(BoomerangError.PARAM_INVALID_TYPE.getReason());
              assertThat(refused.getStatus().value()).isEqualTo(400);
              assertThat(refused.getArgs()[0]).isEqualTo("string");
              assertThat((String) refused.getArgs()[1]).contains("text", "password", "multiselect");
              assertThat(refused.getArgs()[2]).isEqualTo("text");
            });
  }

  @Test
  void aParamWithNoTypeSavesAndSubmitsAsAString() {
    Workflow saved =
        workflowService.create(WORKSPACE, workflow("param-type-absent", declared("region", null)));

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), false)
            .getId();

    assertThat(runParam(runId, "region").getType()).isEqualTo(ParamType.string);
  }

  @Test
  void aStoredParamWhoseTypeNamesNoFieldTypeSubmitsAsAString() {
    Workflow saved =
        workflowService.create(
            WORKSPACE, workflow("param-type-stored", declared("region", "text")));
    String workflowRef =
        relationshipService
            .refsInWorkspace(
                RelationshipType.WORKFLOW, WORKSPACE, Optional.of(List.of(saved.getName())))
            .get(0);
    // A revision saved before save refused unknown types.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("workflowRef").is(workflowRef)),
        new Update().set("params.0.type", "string"),
        WorkflowRevisionEntity.class);

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), false)
            .getId();

    assertThat(runParam(runId, "region").getType()).isEqualTo(ParamType.string);
  }

  private RunParam runParam(String runId, String name) {
    return workflowRunRepository.findById(runId).orElseThrow().getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no param named " + name));
  }

  private static Workflow workflow(String name, AbstractParam param) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + UUID.randomUUID().toString().substring(0, 8));
    workflow.setParams(new LinkedList<>(List.of(param)));
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(afterStart)));
    workflow.setTasks(new LinkedList<>(List.of(start, end)));
    return workflow;
  }

  private static AbstractParam declared(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setDefaultValue("eu");
    return param;
  }
}
