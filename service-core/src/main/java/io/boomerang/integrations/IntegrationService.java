package io.boomerang.integrations;

import tools.jackson.databind.JsonNode;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
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

// H6: integrations is a clean mode-gate root (full-mode-only per the mode matrix).
@Service
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class IntegrationService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final IntegrationTemplateRepository integrationTemplateRepository;
  private final IntegrationsRepository integrationsRepository;
  private final RelationshipService relationshipService;
  private final SettingsService settingsService;
  private final GitHubService gitHubService;
  private final IdentityService identityService;

  public IntegrationService(
      IntegrationTemplateRepository integrationTemplateRepository,
      IntegrationsRepository integrationsRepository,
      RelationshipService relationshipService,
      SettingsService settingsService,
      GitHubService gitHubService,
      IdentityService identityService) {
    this.integrationTemplateRepository = integrationTemplateRepository;
    this.integrationsRepository = integrationsRepository;
    this.relationshipService = relationshipService;
    this.settingsService = settingsService;
    this.gitHubService = gitHubService;
    this.identityService = identityService;
  }

  public List<Integration> get(String workspace) {
    List<IntegrationTemplateEntity> templates =
        integrationTemplateRepository.findAllByStatus("active");
    List<Integration> integrations = new LinkedList<>();
    templates.forEach(
        t -> {
          LOGGER.debug(t.toString());
          Integration i = new Integration();
          BeanUtils.copyProperties(t, i);
          List<String> refs =
              relationshipService.filter(
                  RelationshipType.INTEGRATION,
                  Optional.empty(),
                  Optional.of(RelationshipType.WORKSPACE),
                  Optional.of(List.of(workspace)),
                  false);
          LOGGER.debug("Refs: " + refs.toString());
          if (!refs.isEmpty()) {
            i.setRef(refs.get(0));
            Optional<IntegrationsEntity> entity =
                integrationsRepository.findByIdAndType(refs.get(0), t.getType());
            if (entity.isPresent()) {
              i.setStatus(IntegrationStatus.linked);
            }
          }
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

  public String getTeamByRef(String ref) {
    Optional<IntegrationsEntity> optEntity = integrationsRepository.findByRef(ref);
    if (optEntity.isPresent()) {
      LOGGER.debug("Integration Entity ID: " + optEntity.get().getId());
      String team =
          relationshipService.getParentByLabel(
              RelationshipLabel.HAS_INTEGRATION,
              RelationshipType.INTEGRATION,
              optEntity.get().getId());
      LOGGER.debug("Workspace Ref: " + team);
      if (!team.isBlank()) {
        return team;
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

  public void delete(String type, JsonNode data) {
    Optional<IntegrationsEntity> optEntity =
        integrationsRepository.findByRef(data.get("id").asText());
    if (optEntity.isPresent()) {
      IntegrationsEntity entity = optEntity.get();
      integrationsRepository.delete(optEntity.get());
      relationshipService.removeNodeAndEdgeByRefOrSlug(
          RelationshipType.INTEGRATION, entity.getId());
    }
  }
}
