package io.boomerang.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.Integration;
import io.boomerang.integrations.repository.IntegrationTemplateRepository;
import io.boomerang.integrations.repository.IntegrationsRepository;
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

  private final IntegrationTemplateRepository integrationTemplateRepository;
  private final IntegrationsRepository integrationsRepository;
  private final RelationshipService relationshipService;
  private final SettingsService settingsService;

  public IntegrationService(
      IntegrationTemplateRepository integrationTemplateRepository,
      IntegrationsRepository integrationsRepository,
      RelationshipService relationshipService,
      SettingsService settingsService) {
    this.integrationTemplateRepository = integrationTemplateRepository;
    this.integrationsRepository = integrationsRepository;
    this.relationshipService = relationshipService;
    this.settingsService = settingsService;
  }

  public List<Integration> get(String team) {
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
                  Optional.of(RelationshipType.TEAM),
                  Optional.of(List.of(team)),
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
            LOGGER.debug(
                settingsService.getSettingConfig("integration", "github.appName").getValue());
            i.setLink(
                i.getLink()
                    .replace(
                        "{app_name}",
                        settingsService
                            .getSettingConfig("integration", "github.appName")
                            .getValue()));
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
      LOGGER.debug("Team Ref: " + team);
      if (!team.isBlank()) {
        return team;
      }
    }
    return null;
  }

  public IntegrationsEntity create(String type, JsonNode data) {
    IntegrationsEntity entity = new IntegrationsEntity();
    entity.setType(type);
    entity.setRef(data.get("id").asText());
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
