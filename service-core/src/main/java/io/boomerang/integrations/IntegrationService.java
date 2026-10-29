package io.boomerang.integrations;

import tools.jackson.databind.JsonNode;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.IdentityService;
import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.enums.IntegrationKind;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.Integration;
import io.boomerang.integrations.repository.IntegrationTemplateRepository;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {

  private static final Logger LOGGER = LogManager.getLogger();
  private static final String GITHUB_APP_TYPE = "github_app";

  // The integrations that share credentials with tasks, by the name their routes use.
  private static final Map<String, String> SHARING_TYPES =
      Map.of("github", GITHUB_APP_TYPE, "slack", SlackService.EXTENSION_TYPE);

  private final IntegrationTemplateRepository integrationTemplateRepository;
  private final IntegrationsRepository integrationsRepository;
  private final RelationshipService relationshipService;
  private final SettingsService settingsService;
  private final GitHubService gitHubService;
  private final SlackService slackService;
  private final IdentityService identityService;
  private final IntegrationParameterService integrationParameterService;

  public IntegrationService(
      IntegrationTemplateRepository integrationTemplateRepository,
      IntegrationsRepository integrationsRepository,
      RelationshipService relationshipService,
      SettingsService settingsService,
      GitHubService gitHubService,
      SlackService slackService,
      IdentityService identityService,
      IntegrationParameterService integrationParameterService) {
    this.integrationTemplateRepository = integrationTemplateRepository;
    this.integrationsRepository = integrationsRepository;
    this.relationshipService = relationshipService;
    this.settingsService = settingsService;
    this.gitHubService = gitHubService;
    this.slackService = slackService;
    this.identityService = identityService;
    this.integrationParameterService = integrationParameterService;
  }

  public List<Integration> get(String workspace) {
    List<IntegrationTemplateEntity> templates =
        integrationTemplateRepository.findAllByStatus("active");
    List<Integration> integrations = new LinkedList<>();
    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.INTEGRATION, workspace, Optional.empty());
    LOGGER.debug("Refs: " + refs.toString());
    WorkspaceEntity workspaceEntity = integrationParameterService.workspace(workspace);
    Token identity = identityService.getCurrentIdentity();
    String principal = identity == null ? null : identity.getPrincipal();
    templates.forEach(
        t -> {
          LOGGER.debug(t.toString());
          Integration i = new Integration();
          BeanUtils.copyProperties(t, i);
          if (IntegrationKind.form.equals(t.getKind())) {
            // A form integration is linked while the workspace holds its fields; it has no install.
            i.setLink(null);
            if (workspaceEntity != null && integrationParameterService.isShared(workspaceEntity, t)) {
              i.setStatus(IntegrationStatus.linked);
              i.setShared(true);
              i.setFields(integrationParameterService.fieldValues(workspaceEntity, t));
            }
            integrations.add(i);
            return;
          }
          // A workspace can link several integrations; each template is linked by the one of its type.
          refs.stream()
              .filter(ref -> integrationsRepository.findByIdAndType(ref, t.getType()).isPresent())
              .findFirst()
              .ifPresent(
                  ref -> {
                    i.setRef(ref);
                    i.setStatus(IntegrationStatus.linked);
                    if (workspaceEntity != null
                        && !IntegrationParameterService.fields(t).isEmpty()) {
                      i.setShared(integrationParameterService.isShared(workspaceEntity, t));
                    }
                  });
          if (GITHUB_APP_TYPE.equals(t.getType())) {
            String appName =
                settingsService.getSettingConfig("integration", "github.appName").getValue();
            String link = i.getLink().replace("{app_name}", appName);
            // GitHub warns the installation_id callback parameter can be spoofed, so the state
            // that identifies who is installing and for which workspace is issued and signed
            // here, never trusted from the browser.
            if (principal != null) {
              String state = gitHubService.createSignedState(workspace, principal);
              link = link + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
            }
            i.setLink(link);
          } else if (SlackService.EXTENSION_TYPE.equals(t.getType())) {
            i.setLink(slackService.installLink(t.getLink(), workspace, principal));
          }
          integrations.add(i);
        });
    return integrations;
  }

  /*
   * Share an integration's credentials with the workspace's tasks as managed workspace parameters,
   * or stop sharing them. For an app integration, sharing lets every workflow author in the
   * workspace act with its permissions, so it is a choice made after linking, never part of it. A
   * form integration is linked by sharing the values the workspace enters, and unlinked by
   * stopping; the same call with new values edits them.
   */
  public void share(
      String integration, String workspace, boolean shared, Map<String, String> values) {
    IntegrationTemplateEntity template =
        integrationParameterService.template(SHARING_TYPES.getOrDefault(integration, integration));
    boolean form =
        template != null
            && IntegrationKind.form.equals(template.getKind())
            && "active".equals(template.getStatus());
    if (!form && !SHARING_TYPES.containsKey(integration)) {
      throw new BoomerangException(BoomerangError.INTEGRATION_INVALID_REF);
    }
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF);
    }
    WorkspaceEntity workspaceEntity = integrationParameterService.workspace(workspace);
    if (workspaceEntity == null) {
      throw new BoomerangException(BoomerangError.INTEGRATION_INVALID_REF);
    }
    if (form) {
      if (shared) {
        integrationParameterService.share(workspaceEntity, template, values == null ? Map.of() : values);
      } else {
        integrationParameterService.unshare(workspaceEntity, template);
      }
      return;
    }
    if (values != null && !values.isEmpty()) {
      throw new BoomerangException(BoomerangError.INTEGRATION_VALUES_NOT_ACCEPTED, template.getName());
    }
    String type = SHARING_TYPES.get(integration);
    IntegrationsEntity installation =
        relationshipService
            .refsInWorkspace(RelationshipType.INTEGRATION, workspace, Optional.empty())
            .stream()
            .map(ref -> integrationsRepository.findByIdAndType(ref, type))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new BoomerangException(BoomerangError.INTEGRATION_INVALID_REF));
    if (shared) {
      integrationParameterService.share(
          workspaceEntity,
          template,
          () ->
              (GITHUB_APP_TYPE.equals(type)
                  ? gitHubService.installationToken(installation.getRef())
                  : slackService.sharedToken(installation)));
    } else {
      integrationParameterService.unshare(workspaceEntity, template);
    }
  }

  public String getWorkspaceByRef(String ref) {
    Optional<IntegrationsEntity> optEntity = integrationsRepository.findByRef(ref);
    if (optEntity.isPresent()) {
      LOGGER.debug("Integration Entity ID: " + optEntity.get().getId());
      String workspace =
          relationshipService.getParentByLabel(
              RelationshipLabel.HAS_INTEGRATION,
              RelationshipType.INTEGRATION,
              optEntity.get().getId());
      LOGGER.debug("Workspace Ref: " + workspace);
      if (!workspace.isBlank()) {
        return workspace;
      }
    }
    return null;
  }

  /*
   * Idempotent on ref: the GitHub install callback and the "installation" webhook can both race
   * to persist the same installation, so a second call returns the existing record rather than
   * creating a duplicate node.
   */
  public IntegrationsEntity create(String type, JsonNode data) {
    String ref = data.get("id").asText();
    Optional<IntegrationsEntity> existing = integrationsRepository.findByRef(ref);
    if (existing.isPresent()) {
      return existing.get();
    }

    IntegrationsEntity entity = new IntegrationsEntity();
    entity.setType(type);
    entity.setRef(ref);
    entity.setData(Document.parse(data.toString()));
    entity = integrationsRepository.save(entity);

    relationshipService.createNode(
        RelationshipType.INTEGRATION, entity.getId(), "", Optional.empty());

    return entity;
  }

  /*
   * The installation is gone: its record, its node, and the parameters it managed in the workspace
   * it was linked to.
   */
  public void delete(String type, JsonNode data) {
    Optional<IntegrationsEntity> optEntity =
        integrationsRepository.findByRef(data.get("id").asText());
    if (optEntity.isPresent()) {
      IntegrationsEntity entity = optEntity.get();
      WorkspaceEntity workspace =
          integrationParameterService.workspace(
              relationshipService.getParentByLabel(
                  RelationshipLabel.HAS_INTEGRATION, RelationshipType.INTEGRATION, entity.getId()));
      if (workspace != null) {
        integrationParameterService.unshare(workspace, integrationParameterService.template(type));
      }
      integrationsRepository.delete(optEntity.get());
      relationshipService.removeNodeAndEdgeByRefOrSlug(
          RelationshipType.INTEGRATION, entity.getId());
    }
  }
}
