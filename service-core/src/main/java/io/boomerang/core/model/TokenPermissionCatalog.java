package io.boomerang.core.model;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/*
 * The permission building blocks a caller is entitled to grant for a given scope/principal -
 * backs the token permission picker. Everything returned is sourced from the same
 * PermissionResource / PermissionAction / roles collection the enforcement path itself reads,
 * and is pre-filtered to what the requesting caller could actually grant, so the picker can
 * never offer a combination that isn't enforceable.
 */
@Data
public class TokenPermissionCatalog {

  private List<String> resources = new LinkedList<>();
  private List<String> actions = new LinkedList<>();
  private Map<String, List<String>> rolePresets = new LinkedHashMap<>();
}
