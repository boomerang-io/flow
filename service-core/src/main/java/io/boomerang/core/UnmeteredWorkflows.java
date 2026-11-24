package io.boomerang.core;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.model.Workflow;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The workflows whose runs use none of a workspace's monthly quotas: the {@code quotas} setting
 * {@code unmetered.workflows}, exact names of workflows in the {@code system} workspace separated
 * by commas, and every link whose template is one of them. Names are compared as written, case
 * included; a name with no workflow of the system workspace behind it matches nothing there.
 */
@Component
public class UnmeteredWorkflows {

  public static final String SETTING_KEY = "unmetered.workflows";

  private static final String QUOTAS_SETTINGS_KEY = "quotas";
  private static final String SYSTEM_WORKSPACE = "system";

  private final SettingsService settingsService;
  private final MongoTemplate mongoTemplate;

  public UnmeteredWorkflows(SettingsService settingsService, MongoTemplate mongoTemplate) {
    this.settingsService = settingsService;
    this.mongoTemplate = mongoTemplate;
  }

  /** Return the listed names; none when the setting is blank or missing. */
  public List<String> names() {
    try {
      return parse(settingsService.getSettingConfig(QUOTAS_SETTINGS_KEY, SETTING_KEY).getValue());
    } catch (RuntimeException missing) {
      return List.of();
    }
  }

  /** Return the names in a setting's value: split on commas, each trimmed, blanks dropped. */
  public static List<String> parse(String value) {
    return (value == null)
        ? List.of()
        : Arrays.stream(value.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
  }

  /**
   * Whether a run of the workflow is unmetered, reading the setting. The workflow is named by its
   * owning workspace's name and its own name, when known, and its id.
   */
  public boolean test(String workspace, String workflowName, String workflowRef) {
    return test(names(), workspace, workflowName, workflowRef);
  }

  /**
   * Whether a run of the workflow is unmetered under the given names: no query with no name listed
   * or for a listed workflow of the system workspace whose name is given; otherwise one read of the
   * workflow and, for a link, one of its template.
   */
  public boolean test(
      List<String> names, String workspace, String workflowName, String workflowRef) {
    if (names.isEmpty()) {
      return false;
    }
    boolean system = SYSTEM_WORKSPACE.equals(workspace);
    if (system && workflowName != null && names.contains(workflowName)) {
      return true;
    }
    if (workflowRef == null) {
      return false;
    }
    Query byId = Query.query(where("_id").is(workflowRef));
    byId.fields().include("name").include("templateRef");
    WorkflowEntity workflow = mongoTemplate.findOne(byId, WorkflowEntity.class);
    if (workflow == null) {
      return false;
    }
    if (system && names.contains(workflow.getName())) {
      return true;
    }
    return workflow.getTemplateRef() != null
        && templateNames(List.of(workflow.getTemplateRef().getRef())).values().stream()
            .anyMatch(names::contains);
  }

  /**
   * Return the ids of the unmetered workflows among the given ones, given by owning workspace name:
   * no query with no name listed, else one read of the workflows that could be (the system
   * workspace's listed ones and every link) and one of their templates' names.
   */
  public Set<String> unmeteredRefs(Map<String, List<String>> refsByWorkspace, List<String> names) {
    List<String> refs = refsByWorkspace.values().stream().flatMap(List::stream).toList();
    if (names.isEmpty() || refs.isEmpty()) {
      return Set.of();
    }
    Set<String> system = new HashSet<>(refsByWorkspace.getOrDefault(SYSTEM_WORKSPACE, List.of()));
    Query candidates =
        Query.query(
            where("_id")
                .in(refs)
                .orOperator(where("templateRef").exists(true), where("name").in(names)));
    candidates.fields().include("name").include("templateRef");
    List<WorkflowEntity> workflows = mongoTemplate.find(candidates, WorkflowEntity.class);
    Set<String> unmetered =
        workflows.stream()
            .filter(w -> system.contains(w.getId()) && names.contains(w.getName()))
            .map(WorkflowEntity::getId)
            .collect(Collectors.toCollection(HashSet::new));
    List<WorkflowEntity> links = workflows.stream().filter(w -> w.getTemplateRef() != null).toList();
    if (!links.isEmpty()) {
      Map<String, String> templates =
          templateNames(links.stream().map(link -> link.getTemplateRef().getRef()).toList());
      links.stream()
          .filter(link -> names.contains(templates.get(link.getTemplateRef().getRef())))
          .forEach(link -> unmetered.add(link.getId()));
    }
    return unmetered;
  }

  /**
   * Whether a workflow as the API reads it is unmetered under the given names, with no query: a
   * link names its template, and a workflow of the system workspace, or a template or starter any
   * workspace lists beside its own, is one of the system workspace's.
   */
  public static boolean unmetered(List<String> names, String workspace, Workflow workflow) {
    if (names.isEmpty()) {
      return false;
    }
    if (workflow.getTemplateRef() != null) {
      return names.contains(workflow.getTemplateRef().getName());
    }
    boolean system =
        SYSTEM_WORKSPACE.equals(workspace)
            || (workflow.getType() != null && workflow.getType().isSystemOnly());
    return system && names.contains(workflow.getName());
  }

  // The templates' names by id, in one read.
  private Map<String, String> templateNames(Collection<String> templateRefs) {
    Query templates = Query.query(where("_id").in(templateRefs));
    templates.fields().include("name");
    Map<String, String> names = new HashMap<>();
    mongoTemplate
        .find(templates, WorkflowEntity.class)
        .forEach(template -> names.put(template.getId(), template.getName()));
    return names;
  }
}
