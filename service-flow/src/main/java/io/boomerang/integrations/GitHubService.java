package io.boomerang.integrations;

import com.spotify.github.v3.apps.InstallationRepositoriesResponse;
import com.spotify.github.v3.checks.Installation;
import com.spotify.github.v3.clients.GitHubClient;
import com.spotify.github.v3.clients.GithubAppClient;
import com.spotify.github.v3.clients.OrganisationClient;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.model.GHInstallationsResponse;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.repository.IntegrationsRepository;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GitHubService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final SettingsService settingsService;
  private final RelationshipService relationshipService;
  private final IntegrationsRepository integrationsRepository;

  public GitHubService(
      SettingsService settingsService,
      RelationshipService relationshipService,
      IntegrationsRepository integrationsRepository) {
    this.settingsService = settingsService;
    this.relationshipService = relationshipService;
    this.integrationsRepository = integrationsRepository;
  }

  public ResponseEntity<?> getInstallation(Integer id) {
    final GithubAppClient appClient = getGitHubAppClient(id);
    try {
      List<Installation> installations = appClient.getInstallations().join();
      LOGGER.debug("GitHub Installation: " + installations.toString());

      InstallationRepositoriesResponse repositories =
          appClient.listAccessibleRepositories(installations.get(0).id()).join();

      GHInstallationsResponse response = new GHInstallationsResponse();
      response.setAppId(Integer.valueOf(installations.get(0).appId()));
      response.setInstallationId(Integer.valueOf(installations.get(0).id()));
      response.setOrgSlug(installations.get(0).account().login());
      response.setOrgUrl(installations.get(0).account().htmlUrl().toString());
      response.setOrgId(Integer.valueOf(installations.get(0).account().id()));
      response.setOrgType(installations.get(0).account().type());
      response.setEvents(installations.get(0).events());
      response.setRepositories(repositories.repositories().stream().map(r -> r.name()).toList());
      return ResponseEntity.ok(response);
    } catch (Exception ex) {
      throw new BoomerangException(ex, BoomerangError.ACTION_INVALID_REF);
    }
  }

  public ResponseEntity<?> getInstallationForTeam(String team) {
    List<String> refs =
        relationshipService.findNodeRefs(RelationshipType.TEAM, team, RelationshipType.INTEGRATION);
    if (!refs.isEmpty()) {
      Optional<IntegrationsEntity> optEntity = integrationsRepository.findById(refs.get(0));
      if (optEntity.isPresent()) {
        return this.getInstallation(Integer.valueOf(optEntity.get().getRef()));
      }
    }
    throw new BoomerangException(BoomerangError.ACTION_INVALID_REF);
  }

  private GithubAppClient getGitHubAppClient(Integer installationId) {
    final String appId = settingsService.getSettingConfig("integration", "github.appId").getValue();
    final GitHubClient githubClient =
        GitHubClient.create(
            URI.create("https://api.github.com/"),
            this.getPEMBytes(),
            Integer.valueOf(appId),
            installationId);

    final OrganisationClient orgClient = githubClient.createOrganisationClient("");

    final GithubAppClient appClient = orgClient.createGithubAppClient();
    return appClient;
  }

  private byte[] getPEMBytes() {
    final String pem = settingsService.getSettingConfig("integration", "github.jwt").getValue();
    final String RSA_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    final String RSA_END = "-----END RSA PRIVATE KEY-----";

    String middle = pem.replace(RSA_BEGIN, "").replace(RSA_END, "").trim();
    String[] split = middle.split(" ");

    StringBuilder builder = new StringBuilder();
    builder.append(RSA_BEGIN).append("\n");
    for (String s : split) {
      builder.append(s).append("\n");
    }
    builder.append(RSA_END);

    return builder.toString().getBytes();
  }

  public ResponseEntity<?> linkAppInstallation(GHLinkRequest request) {
    LOGGER.debug("linkAppInstallation() - " + request.toString());
    GithubAppClient appClient = getGitHubAppClient(Integer.valueOf(request.getRef()));
    List<Installation> installations = appClient.getInstallations().join();
    Optional<IntegrationsEntity> optEntity =
        integrationsRepository.findByRef(String.valueOf(installations.get(0).id()));
    if (optEntity.isPresent()) {
      IntegrationsEntity entity = optEntity.get();
      if (relationshipService
          .filter(
              RelationshipType.INTEGRATION,
              Optional.of(List.of(entity.getId())),
              Optional.of(RelationshipType.TEAM),
              Optional.of(List.of(request.getTeam())))
          .isEmpty()) {
        relationshipService.createEdge(
            RelationshipType.TEAM,
            request.getTeam(),
            RelationshipLabel.HAS_INTEGRATION,
            RelationshipType.INTEGRATION,
            entity.getId(),
            Optional.empty());
      }
      return ResponseEntity.ok().build();
    }
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  public void unlinkAppInstallation(GHLinkRequest request) {
    LOGGER.debug("unlinkAppInstallation() - " + request.toString());
    Optional<IntegrationsEntity> optEntity = integrationsRepository.findById(request.getRef());
    if (optEntity.isPresent()) {
      IntegrationsEntity entity = optEntity.get();
      relationshipService.removeNodeAndEdgeByRefOrSlug(
          RelationshipType.INTEGRATION, entity.getId());
    }
  }
}
