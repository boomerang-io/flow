package io.boomerang.integrations;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.SecretCipher;
import io.boomerang.core.SettingsService;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import io.boomerang.integrations.repository.IntegrationTemplateRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.BeanUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The workspace parameters an integration manages: its template's {@code fields}, written into a
 * workspace when the integration's credentials are shared with tasks, so a task reads them as
 * {@code $(workspace.params.<name>)} like any other. A managed parameter is stored read-only;
 * nothing but this service writes or removes it.
 */
@Service
public class IntegrationParameterService {

  private static final String PASSWORD = DataAdapterUtil.FieldType.PASSWORD.value();

  private final IntegrationTemplateRepository integrationTemplateRepository;
  private final WorkspaceRepository workspaceRepository;
  private final SettingsService settingsService;
  private final MongoTemplate mongoTemplate;
  private final SecretCipher cipher;
  private final ParamLayerCache paramLayerCache;

  public IntegrationParameterService(
      IntegrationTemplateRepository integrationTemplateRepository,
      WorkspaceRepository workspaceRepository,
      SettingsService settingsService,
      MongoTemplate mongoTemplate,
      SecretCipher cipher,
      ParamLayerCache paramLayerCache) {
    this.integrationTemplateRepository = integrationTemplateRepository;
    this.workspaceRepository = workspaceRepository;
    this.settingsService = settingsService;
    this.mongoTemplate = mongoTemplate;
    this.cipher = cipher;
    this.paramLayerCache = paramLayerCache;
  }

  /** Return the template of this integration type; null when there is none. */
  public IntegrationTemplateEntity template(String type) {
    return integrationTemplateRepository.findFirstByType(type).orElse(null);
  }

  /**
   * Return the integration managing this stored parameter: one whose template declares its name,
   * with the parameter read-only. A read-only parameter no integration declares is not managed.
   */
  public Optional<IntegrationTemplateEntity> managedBy(AbstractParam param) {
    if (param == null || !param.isReadOnly()) {
      return Optional.empty();
    }
    return integrationTemplateRepository.findAll().stream()
        .filter(
            template ->
                fields(template).stream()
                    .anyMatch(field -> field.getName().equalsIgnoreCase(param.getName())))
        .findFirst();
  }

  /** Return whether the workspace holds every one of the template's fields as a managed parameter. */
  public boolean isShared(WorkspaceEntity workspace, IntegrationTemplateEntity template) {
    List<AbstractParam> fields = fields(template);
    return !fields.isEmpty()
        && fields.stream()
            .allMatch(field -> stored(workspace, field.getName()).filter(AbstractParam::isReadOnly).isPresent());
  }

  /**
   * Write the template's fields into the workspace as managed parameters: each {@code password}
   * field holds the credential, any other its default. Checked before the credential is asked for:
   * workspace parameters must be on, and a parameter of the same name the workspace already holds,
   * and no integration manages, is never overwritten.
   */
  public void share(
      WorkspaceEntity workspace, IntegrationTemplateEntity template, Supplier<String> credential) {
    checkShareable(workspace, template);
    String secret = credential.get();
    write(
        workspace,
        template,
        field -> (PASSWORD.equals(field.getType()) ? secret : field.getDefaultValue()));
  }

  /**
   * Write a form integration's fields from the values the workspace entered. A secret sent as the
   * redaction marker, or left out, keeps the one stored, and any other field left out keeps its
   * stored value or takes its default, so the same call edits the values later. A name the template
   * does not declare is refused, and every required field must end with a value.
   */
  public void share(
      WorkspaceEntity workspace, IntegrationTemplateEntity template, Map<String, String> values) {
    checkShareable(workspace, template);
    List<AbstractParam> fields = fields(template);
    values.keySet().stream()
        .filter(name -> fields.stream().noneMatch(field -> field.getName().equalsIgnoreCase(name)))
        .findFirst()
        .ifPresent(
            name -> {
              throw new BoomerangException(
                  BoomerangError.INTEGRATION_FIELD_UNKNOWN, name, template.getName());
            });
    Map<String, Object> resolved = new HashMap<>();
    for (AbstractParam field : fields) {
      Object stored = stored(workspace, field.getName()).filter(AbstractParam::isReadOnly).map(AbstractParam::getValue).orElse(null);
      String incoming =
          values.entrySet().stream()
              .filter(entry -> entry.getKey().equalsIgnoreCase(field.getName()))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElse(null);
      Object value =
          PASSWORD.equals(field.getType())
              ? DataAdapterUtil.keepSecretValue(incoming, stored)
              : (incoming != null ? incoming : stored != null ? stored : field.getDefaultValue());
      if (Boolean.TRUE.equals(field.getRequired()) && (value == null || value.toString().isBlank())) {
        throw new BoomerangException(
            BoomerangError.INTEGRATION_FIELD_REQUIRED, field.getName(), template.getName());
      }
      resolved.put(field.getName(), value);
    }
    write(workspace, template, field -> resolved.get(field.getName()));
  }

  /** Remove the template's managed parameters from the workspace; parameters a user made stay. */
  public void unshare(WorkspaceEntity workspace, IntegrationTemplateEntity template) {
    List<AbstractParam> fields = fields(template);
    List<AbstractParam> parameters = new LinkedList<>(parameters(workspace));
    Map<String, Object> secretsBefore = SecretChangeHolder.secretValues("", parameters, PASSWORD);
    boolean removed =
        parameters.removeIf(
            p ->
                p.isReadOnly()
                    && fields.stream().anyMatch(field -> field.getName().equalsIgnoreCase(p.getName())));
    if (removed) {
      SecretChangeHolder.compare(secretsBefore, SecretChangeHolder.secretValues("", parameters, PASSWORD));
      workspace.setParameters(parameters);
      workspaceRepository.save(workspace);
      paramLayerCache.evictAll();
    }
  }

  // Workspace parameters must be on, and a parameter a user made under a field's name is never
  // overwritten.
  private void checkShareable(WorkspaceEntity workspace, IntegrationTemplateEntity template) {
    if (!settingsService.getSettingConfig("features", "workspaceParameters").getBooleanValue()) {
      throw new BoomerangException(BoomerangError.INTEGRATION_PARAMS_DISABLED, template.getName());
    }
    for (AbstractParam field : fields(template)) {
      stored(workspace, field.getName())
          .filter(existing -> !existing.isReadOnly())
          .ifPresent(
              existing -> {
                throw new BoomerangException(
                    BoomerangError.INTEGRATION_PARAM_EXISTS, existing.getName(), template.getName());
              });
    }
  }

  // Replace the template's fields with managed parameters holding these values; the audit event of
  // the call names the secrets that changed, never their values.
  private void write(
      WorkspaceEntity workspace,
      IntegrationTemplateEntity template,
      Function<AbstractParam, Object> valueOf) {
    List<AbstractParam> parameters = new LinkedList<>(parameters(workspace));
    Map<String, Object> secretsBefore = SecretChangeHolder.secretValues("", parameters, PASSWORD);
    for (AbstractParam field : fields(template)) {
      parameters.removeIf(p -> p.getName().equalsIgnoreCase(field.getName()));
      AbstractParam managed = new AbstractParam();
      BeanUtils.copyProperties(field, managed);
      managed.setValue(valueOf.apply(field));
      managed.setDefaultValue(null);
      managed.setReadOnly(true);
      parameters.add(managed);
    }
    SecretChangeHolder.compare(secretsBefore, SecretChangeHolder.secretValues("", parameters, PASSWORD));
    workspace.setParameters(parameters);
    workspaceRepository.save(workspace);
    paramLayerCache.evictAll();
  }

  /**
   * Return a form integration's fields with the values the workspace holds, a set secret as the
   * redaction marker. Copies, so the template is never changed.
   */
  public List<AbstractParam> fieldValues(WorkspaceEntity workspace, IntegrationTemplateEntity template) {
    List<AbstractParam> fields = new LinkedList<>();
    for (AbstractParam field : fields(template)) {
      AbstractParam shown = new AbstractParam();
      BeanUtils.copyProperties(field, shown);
      Object value =
          stored(workspace, field.getName()).filter(AbstractParam::isReadOnly).map(AbstractParam::getValue).orElse(null);
      shown.setValue(PASSWORD.equals(field.getType()) ? DataAdapterUtil.maskSecretValue(value) : value);
      fields.add(shown);
    }
    return fields;
  }

  /**
   * Replace a managed parameter's value with one targeted write: no other parameter is read or
   * written, so a user's concurrent edit is never lost, and no audit event is raised. A {@code
   * password} value is encrypted here, as the write bypasses the save hook. Return whether a
   * managed parameter of that name was there to update.
   */
  public boolean refresh(WorkspaceEntity workspace, AbstractParam field, String value) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(workspace.getId())
                .and("parameters")
                .elemMatch(Criteria.where("name").is(field.getName()).and("readOnly").is(true)));
    Object stored = PASSWORD.equals(field.getType()) ? cipher.encrypt(value) : value;
    boolean updated =
        mongoTemplate
                .updateFirst(query, new Update().set("parameters.$.value", stored), WorkspaceEntity.class)
                .getMatchedCount()
            > 0;
    paramLayerCache.evictAll();
    return updated;
  }

  /** Return the workspace by id, else by name; null when there is neither. */
  public WorkspaceEntity workspace(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      return null;
    }
    return workspaceRepository
        .findById(workspace)
        .or(() -> workspaceRepository.findByNameIgnoreCase(workspace))
        .orElse(null);
  }

  public static List<AbstractParam> fields(IntegrationTemplateEntity template) {
    return (template != null && template.getFields() != null ? template.getFields() : List.of());
  }

  private static List<AbstractParam> parameters(WorkspaceEntity workspace) {
    return (workspace.getParameters() != null ? workspace.getParameters() : List.of());
  }

  private static Optional<AbstractParam> stored(WorkspaceEntity workspace, String name) {
    return parameters(workspace).stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst();
  }
}
