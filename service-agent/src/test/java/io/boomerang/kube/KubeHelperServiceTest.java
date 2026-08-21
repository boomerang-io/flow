package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.error.BoomerangException;
import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles({"local"})
public class KubeHelperServiceTest {

  @Autowired private KubeHelperService helperKubeService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  private static Optional<EnvVar> findEnv(List<EnvVar> envVars, String name) {
    return envVars.stream().filter(e -> name.equals(e.getName())).findFirst();
  }

  @Test
  public void testCreateTaskEnvVarsMapsParamsToPrefixedEnv() {
    List<RunParam> params =
        List.of(
            new RunParam("string-param", "hello"),
            new RunParam("object-param", Map.of("key", "value")),
            new RunParam("null-param", null));

    List<EnvVar> envVars =
        helperKubeService.createTaskEnvVars(false, params, List.of());

    assertEquals("hello", findEnv(envVars, "PARAM_STRING_PARAM").orElseThrow().getValue());
    assertEquals(
        new ObjectMapper().writeValueAsString(Map.of("key", "value")),
        findEnv(envVars, "PARAM_OBJECT_PARAM").orElseThrow().getValue());
    assertEquals("", findEnv(envVars, "PARAM_NULL_PARAM").orElseThrow().getValue());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCreateTaskEnvVarsExposesLosslessParamsJson() {
    List<RunParam> params =
        List.of(
            new RunParam("privateKey", "secret"),
            new RunParam("my-param", 42),
            new RunParam("flags", Map.of("dryRun", true)),
            new RunParam("missing", null));

    List<EnvVar> envVars = helperKubeService.createTaskEnvVars(false, params, List.of());

    Map<String, Object> parsed =
        new ObjectMapper()
            .readValue(findEnv(envVars, "PARAMS").orElseThrow().getValue(), Map.class);
    assertEquals("secret", parsed.get("privateKey"));
    assertEquals(42, parsed.get("my-param"));
    assertEquals(Map.of("dryRun", true), parsed.get("flags"));
    assertTrue(parsed.containsKey("missing"));
    assertEquals(null, parsed.get("missing"));
  }

  @Test
  public void testCreateTaskEnvVarsExplicitTaskEnvVarWinsOverGeneratedParam() {
    List<RunParam> params = List.of(new RunParam("greeting", "hello"));
    List<TaskEnvVar> envVars = List.of(new TaskEnvVar("PARAM_GREETING", "overridden"));

    List<EnvVar> result = helperKubeService.createTaskEnvVars(false, params, envVars);

    assertEquals("overridden", findEnv(result, "PARAM_GREETING").orElseThrow().getValue());
  }

  @Test
  public void testCreateTaskEnvVarsIncludesRuntimeVars() {
    EnvVar runtimeVar = helperKubeService.createEnvVar("RESULTS_PATH", "/dev/termination-log");

    List<EnvVar> result = helperKubeService.createTaskEnvVars(false, List.of(), List.of(), runtimeVar);

    assertTrue(
        findEnv(result, "RESULTS_PATH")
            .map(e -> "/dev/termination-log".equals(e.getValue()))
            .orElse(false));
  }

  @Test
  public void testCreateTaskEnvVarsThrowsOnParamNameCollision() {
    List<RunParam> params =
        List.of(new RunParam("my-param", "a"), new RunParam("my_param", "b"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> helperKubeService.createTaskEnvVars(false, params, List.of()));

    assertTrue(ex.getDescription().contains("PARAM_NAME_COLLISION"));
    assertTrue(ex.getDescription().contains("PARAM_MY_PARAM"));
  }
}
