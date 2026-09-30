package io.boomerang.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamLayersTest {

  @Test
  void workspaceParamsResolveUnderBothScopesButOnlyTheCurrentOneIsSuggested() {
    ParamLayers layers = new ParamLayers();
    layers.getTeamParams().putAll(Map.of("token", "t"));

    Map<String, Object> flat = layers.getFlatMap();
    assertEquals("t", flat.get("workspace.params.token"));
    assertEquals("t", flat.get("team.params.token"), "the deprecated spelling still resolves");
    assertEquals("t", flat.get("params.token"));

    List<String> suggested = layers.getFlatKeys();
    assertTrue(suggested.contains("workspace.params.token"));
    assertFalse(suggested.contains("team.params.token"), "the deprecated spelling is not suggested");
  }
}
