package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.entity.GlobalParamEntity;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.json.JsonMapper;

/**
 * A password-typed global or workspace parameter's default is treated like its value: ciphertext
 * at rest, never returned by a read, and kept when an update sends it back blank or as the
 * redaction marker. Resolution reads only the value of these layers, which stays plaintext in
 * memory.
 */
class SecretParameterDefaultTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String LABEL = "crypt_v1{AESGCM|";

  @Autowired private ParameterService parameterService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private ParamLayerService paramLayerService;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void enableParameterLayers() {
    setFeatureSetting("globalParameters", true);
    setFeatureSetting("workspaceParameters", true);
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void aGlobalPasswordDefaultIsCiphertextAtRestAndNeverRead() {
    AbstractParam created =
        parameterService.create(param("defGlobalToken", "glob-def-0001", "glob-val-0001"));

    assertThat(json(created)).doesNotContain("glob-def-0001").doesNotContain("glob-val-0001");
    Document stored = rawGlobal("defGlobalToken");
    assertThat(stored.getString("defaultValue")).startsWith(LABEL);
    assertThat(stored.getString("value")).startsWith(LABEL);
    assertThat(stored.toJson()).doesNotContain("glob-def-0001").doesNotContain("glob-val-0001");

    assertThat(json(parameterService.getAll()))
        .doesNotContain("glob-def-0001")
        .doesNotContain("glob-val-0001")
        .doesNotContain("crypt_v1");

    AbstractParam inMemory = unfilteredGlobal("defGlobalToken");
    assertThat(inMemory.getDefaultValue()).isEqualTo("glob-def-0001");
    ParamLayers layers = paramLayerService.buildParamLayers(null, (String) null, null);
    assertThat(layers.getGlobalParams()).containsEntry("defGlobalToken", "glob-val-0001");
  }

  @Test
  void aGlobalUpdateSendingTheMarkerOrNoValueKeepsTheStoredDefaultAndABlankClearsIt() {
    parameterService.create(param("defGlobalKeep", "glob-def-0002", "glob-val-0002"));

    for (Object sentBack : new Object[] {DataAdapterUtil.REDACTED, null}) {
      AbstractParam edit = param("defGlobalKeep", sentBack, sentBack);
      edit.setDescription("edited " + sentBack);
      AbstractParam updated = parameterService.update(edit);

      assertThat(json(updated)).doesNotContain("glob-def-0002").doesNotContain("glob-val-0002");
      AbstractParam stored = unfilteredGlobal("defGlobalKeep");
      assertThat(stored.getDefaultValue()).as("sent back %s", sentBack).isEqualTo("glob-def-0002");
      assertThat(stored.getValue()).as("sent back %s", sentBack).isEqualTo("glob-val-0002");
      assertThat(stored.getDescription()).isEqualTo("edited " + sentBack);
    }

    AbstractParam updated = parameterService.update(param("defGlobalKeep", "glob-def-0003", null));
    assertThat(json(updated)).doesNotContain("glob-def-0003");
    assertThat(unfilteredGlobal("defGlobalKeep").getDefaultValue()).isEqualTo("glob-def-0003");
    assertThat(rawGlobal("defGlobalKeep").getString("defaultValue")).startsWith(LABEL);

    parameterService.update(param("defGlobalKeep", "", ""));
    assertThat(unfilteredGlobal("defGlobalKeep").getDefaultValue()).isEqualTo("");
    assertThat(unfilteredGlobal("defGlobalKeep").getValue()).isEqualTo("");
  }

  @Test
  void aWorkspacePasswordDefaultIsCiphertextAtRestAndNeverRead() {
    String name = "secret-default-ws";
    WorkspaceRequest create = new WorkspaceRequest();
    create.setName(name);
    create.setDisplayName(name);
    create.setParameters(List.of(param("wsDbPass", "ws-def-0001", "ws-val-0001")));
    Workspace created = workspaceService.create(create);

    assertThat(json(created)).doesNotContain("ws-def-0001").doesNotContain("ws-val-0001");
    Document stored = workspaceParam(name, "wsDbPass");
    assertThat(stored.getString("defaultValue")).startsWith(LABEL);
    assertThat(stored.getString("value")).startsWith(LABEL);

    assertThat(json(workspaceService.get(name)))
        .doesNotContain("ws-def-0001")
        .doesNotContain("ws-val-0001")
        .doesNotContain("crypt_v1");
    assertThat(
            json(
                workspaceService
                    .query(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(name)))
                    .getContent()))
        .contains(name)
        .doesNotContain("ws-def-0001")
        .doesNotContain("ws-val-0001")
        .doesNotContain("crypt_v1");

    WorkspaceEntity inMemory = workspaceRepository.findByNameIgnoreCase(name).orElseThrow();
    assertThat(named(inMemory.getParameters(), "wsDbPass").getDefaultValue()).isEqualTo("ws-def-0001");
    ParamLayers layers = paramLayerService.buildParamLayers(name, (String) null, null);
    assertThat(layers.getWorkspaceParams()).containsEntry("wsDbPass", "ws-val-0001");
  }

  @Test
  void aWorkspacePatchSendingTheMarkerOrNoValueKeepsTheStoredDefaultAndABlankClearsIt() {
    String name = "secret-default-keep-ws";
    WorkspaceRequest create = new WorkspaceRequest();
    create.setName(name);
    create.setDisplayName(name);
    create.setParameters(List.of(param("wsKeepPass", "ws-def-0002", "ws-val-0002")));
    workspaceService.create(create);

    for (Object sentBack : new Object[] {DataAdapterUtil.REDACTED, null}) {
      AbstractParam edit = param("wsKeepPass", sentBack, sentBack);
      edit.setDescription("edited " + sentBack);
      WorkspaceRequest patch = new WorkspaceRequest();
      patch.setParameters(List.of(edit));
      Workspace patched = workspaceService.patch(name, patch);

      assertThat(json(patched)).doesNotContain("ws-def-0002").doesNotContain("ws-val-0002");
      AbstractParam stored =
          named(workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getParameters(), "wsKeepPass");
      assertThat(stored.getDefaultValue()).as("sent back %s", sentBack).isEqualTo("ws-def-0002");
      assertThat(stored.getValue()).as("sent back %s", sentBack).isEqualTo("ws-val-0002");
      assertThat(stored.getDescription()).isEqualTo("edited " + sentBack);
    }

    WorkspaceRequest replace = new WorkspaceRequest();
    replace.setParameters(List.of(param("wsKeepPass", "ws-def-0003", null)));
    workspaceService.patch(name, replace);
    assertThat(
            named(workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getParameters(), "wsKeepPass")
                .getDefaultValue())
        .isEqualTo("ws-def-0003");
    assertThat(workspaceParam(name, "wsKeepPass").getString("defaultValue")).startsWith(LABEL);

    WorkspaceRequest clear = new WorkspaceRequest();
    clear.setParameters(List.of(param("wsKeepPass", "", "")));
    workspaceService.patch(name, clear);
    AbstractParam cleared =
        named(workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getParameters(), "wsKeepPass");
    assertThat(cleared.getDefaultValue()).isEqualTo("");
    assertThat(cleared.getValue()).isEqualTo("");
  }

  @Test
  void aWorkspacePatchWithACaseVariantNameReplacesTheParameterInsteadOfAddingOne() {
    String name = "secret-case-variant-ws";
    WorkspaceRequest create = new WorkspaceRequest();
    create.setName(name);
    create.setDisplayName(name);
    create.setParameters(List.of(param("wsCaseToken", "ws-case-def", "ws-case-val")));
    workspaceService.create(create);

    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setParameters(List.of(param("WSCASETOKEN", DataAdapterUtil.REDACTED, DataAdapterUtil.REDACTED)));
    workspaceService.patch(name, patch);

    List<AbstractParam> stored =
        workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getParameters();
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getValue()).isEqualTo("ws-case-val");
    assertThat(stored.get(0).getDefaultValue()).isEqualTo("ws-case-def");
  }

  private AbstractParam unfilteredGlobal(String name) {
    return named(parameterService.getAllUnfiltered(), name);
  }

  private Document rawGlobal(String name) {
    Document document =
        mongoTemplate.findOne(
            Query.query(Criteria.where("name").is(name)),
            Document.class,
            mongoTemplate.getCollectionName(GlobalParamEntity.class));
    assertThat(document).isNotNull();
    return document;
  }

  private Document workspaceParam(String workspace, String name) {
    Document document =
        mongoTemplate.findOne(
            Query.query(Criteria.where("name").is(workspace)),
            Document.class,
            mongoTemplate.getCollectionName(WorkspaceEntity.class));
    assertThat(document).isNotNull();
    return document.getList("parameters", Document.class).stream()
        .filter(p -> name.equals(p.getString("name")))
        .findFirst()
        .orElseThrow();
  }

  private static AbstractParam named(List<AbstractParam> params, String name) {
    return params.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
  }

  private static String json(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  private static AbstractParam param(String name, Object defaultValue, Object value) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("password");
    param.setDefaultValue(defaultValue);
    param.setValue(value);
    return param;
  }
}
