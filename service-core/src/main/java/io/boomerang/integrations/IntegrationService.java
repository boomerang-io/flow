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
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.Integration;
import io.boomerang.integrations.repository.IntegrationTemplateRepository;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
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

  private final IntegrationTemplateRepository integrationTemplateRepository;
  private final IntegrationsRepository integrationsRepository;
  private final RelationshipService relationshipService;
  private final SettingsService settingsService;
  private final GitHubService gitHubService;
  private final IdentityService identityService;
  private final IntegrationParameterService integrationParameterService;

  public IntegrationService(
      IntegrationTemplateRepository integrationTemplateRepository,
      IntegrationsRepository integrationsRepository,
      RelationshipService relationshipService,
      SettingsService settingsService,
      GitHubService gitHubService,
      IdentityService identityService,
      IntegrationParameterService integrationParameterService) {
    this.integrationTemplateRepository = integrationTemplateRepository;
    this.integrationsRepository = integrationsRepository;
    this.relationshipService = relationshipService;
    this.settingsService = settingsService;
    this.gitHubService = gitHubService;
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
    templates.forEach(
        t -> {
          LOGGER.debug(t.toString());
          Integration i = new Integration();
          BeanUtils.copyProperties(t, i);
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
          if ("github".equals(i.getName().toLowerCase())) {
            String appName =
                settingsService.getSettingConfig("integration", "github.appName").getValue();
            String link = i.getLink().replace("{app_name}", appName);
            // GitHub warns the installation_id callback parameter can be spoofed, so the state
            // that identifies who is installing and for which workspace is issued and signed
            // here, never trusted from the browser.
            Token identity = identityService.getCurrentIdentity();
            if (identity != null && identity.getPrincipal() != null) {
              String state = gitHubService.createSignedState(workspace, identity.getPrincipal());
              link = link + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
            }
            i.setLink(link);
          }
          integrations.add(i);
        });
    return integrations;
  }

  /*
   * Share the GitHub App installation token with the workspace's tasks as managed workspace
   * parameters, or stop sharing it. Sharing lets every workflow author in the workspace act with the
   * App's permissions, so it is a choice made after linking, never part of it.
   */
  public void shareGitHub(String workspace, boolean shared) {
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF);
    }
    WorkspaceEntity workspaceEntity = integrationParameterService.workspace(workspace);
    IntegrationsEntity installation =
        relationshipService
            .refsInWorkspace(RelationshipType.INTEGRATION, workspace, Optional.empty())
            .stream()
            .map(ref -> integrationsRepository.findByIdAndType(ref, GITHUB_APP_TYPE))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    if (workspaceEntity == null || installation == null) {
      throw new BoomerangException(BoomerangError.INTEGRATION_INVALID_REF);
    }
    IntegrationTemplateEntity template = integrationParameterService.template(GITHUB_APP_TYPE);
    if (shared) {
      integrationParameterService.share(
          workspaceEntity, template, () -> gitHubService.installationToken(installation.getRef()));
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
