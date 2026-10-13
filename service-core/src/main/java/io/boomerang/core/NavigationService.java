package io.boomerang.core;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.NavigationType;
import io.boomerang.core.model.Features;
import io.boomerang.core.model.Navigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class NavigationService {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";
  private static final String SINGLE_WORKSPACE = "system";

  @Value("${flow.externalUrl.navigation}")
  private String flowExternalUrlNavigation;

  @Value("${flow.apps.flow.url}")
  private String flowAppsUrl;

  private final ExternalTokenService apiTokenService;
  private final RestTemplate restTemplate;
  private final FeatureService featureService;
  private final UserService userService;

  public NavigationService(
      @Qualifier("internalRestTemplate") RestTemplate restTemplate,
      ExternalTokenService apiTokenService,
      FeatureService featureService,
      UserService userService) {
    this.restTemplate = restTemplate;
    this.apiTokenService = apiTokenService;
    this.featureService = featureService;
    this.userService = userService;
  }

  public List<Navigation> getNavigation(boolean isUserAdmin, Optional<String> optTeamId) {

    Features features = featureService.get();

    // A single-workspace install has one workspace, so its links never wait for one to be chosen.
    Optional<String> workspace =
        (Boolean.TRUE.equals(features.getFeatures().get("workspace.single"))
            ? Optional.of(SINGLE_WORKSPACE)
            : optTeamId);
    boolean disabled = workspace.isEmpty();
    String teamIdURLContext = workspace.map(name -> "/" + name).orElse("");

    if (flowExternalUrlNavigation.isBlank()) {
      List<Navigation> response = new ArrayList<>();
      Navigation home = new Navigation();
      home.setName("Home");
      home.setType(NavigationType.link);
      home.setDisabled(false);
      home.setIcon("Home");
      home.setLink(flowAppsUrl + "/home");
      response.add(home);

      Navigation divider = new Navigation();
      divider.setType(NavigationType.divider);
      response.add(divider);

      Navigation workflows = new Navigation();
      workflows.setName("Workflows");
      workflows.setType(NavigationType.link);
      workflows.setDisabled(disabled);
      workflows.setIcon("FlowData");
      workflows.setLink(flowAppsUrl + teamIdURLContext + "/workflows");
      response.add(workflows);

      if (((Boolean) features.getFeatures().get("activity"))) {
        Navigation activity = new Navigation();
        activity.setName("Activity");
        activity.setType(NavigationType.link);
        activity.setDisabled(disabled);
        activity.setIcon("Activity");
        activity.setLink(flowAppsUrl + teamIdURLContext + "/activity");
        response.add(activity);
      }

      Navigation actions = new Navigation();
      actions.setName("Actions");
      actions.setType(NavigationType.link);
      actions.setDisabled(disabled);
      actions.setIcon("Stamp");
      actions.setLink(flowAppsUrl + teamIdURLContext + "/actions");
      response.add(actions);

      if (((Boolean) features.getFeatures().get("insights"))) {
        Navigation insights = new Navigation();
        insights.setName("Insights");
        insights.setType(NavigationType.link);
        insights.setDisabled(disabled);
        insights.setIcon("ChartScatter");
        insights.setLink(flowAppsUrl + teamIdURLContext + "/insights");
        response.add(insights);
      }

      if (((Boolean) features.getFeatures().get("schedules"))) {
        Navigation schedules = new Navigation();
        schedules.setName("Schedules");
        schedules.setType(NavigationType.link);
        schedules.setDisabled(disabled);
        schedules.setIcon("CalendarHeatMap");
        schedules.setLink(flowAppsUrl + teamIdURLContext + "/schedules");
        response.add(schedules);
      }

      if (((Boolean) features.getFeatures().get("integrations"))) {
        Navigation integrations = new Navigation();
        integrations.setName("Integrations");
        integrations.setType(NavigationType.link);
        integrations.setDisabled(disabled);
        integrations.setIcon("AppConnectivity");
        integrations.setLink(flowAppsUrl + teamIdURLContext + "/integrations");
        integrations.setBeta(true);
        response.add(integrations);
      }

      response.add(divider);

      if (((Boolean) features.getFeatures().get("workspace.tasks"))) {
        Navigation teamTasks = new Navigation();
        teamTasks.setName("Task Manager");
        teamTasks.setType(NavigationType.link);
        teamTasks.setDisabled(disabled);
        teamTasks.setIcon("TaskSettings");
        teamTasks.setLink(flowAppsUrl + teamIdURLContext + "/task-manager");
        response.add(teamTasks);
      }

      if (((Boolean) features.getFeatures().get("workspace.parameters"))) {
        Navigation workspaceParameters = new Navigation();
        workspaceParameters.setName("Parameters");
        workspaceParameters.setType(NavigationType.link);
        workspaceParameters.setDisabled(disabled);
        workspaceParameters.setIcon("Parameter");
        workspaceParameters.setLink(flowAppsUrl + teamIdURLContext + "/parameters");
        response.add(workspaceParameters);
      }

      if (((Boolean) features.getFeatures().get("workspace.management"))) {
        Navigation management = new Navigation();
        management.setName("Manage Workspace");
        management.setType(NavigationType.link);
        management.setDisabled(disabled);
        management.setIcon("SettingsAdjust");
        management.setLink(flowAppsUrl + teamIdURLContext + "/manage");
        response.add(management);
      }

      // One link: the Manage area is a tabbed page, and the webapp decides which tabs to show
      // from the caller's grants and the feature flags (client-web Features/Manage).
      if (isUserAdmin) {
        Navigation manage = new Navigation();
        manage.setName("Manage");
        manage.setType(NavigationType.link);
        manage.setIcon("Settings");
        manage.setLink(flowAppsUrl + "/admin");
        response.add(manage);
      }

      return response;
    } else {

      UriComponentsBuilder uriComponentsBuilder =
          UriComponentsBuilder.fromUriString(flowExternalUrlNavigation);
      UriComponents uriComponents = null;

      if (optTeamId.isEmpty() || optTeamId.get().isBlank()) {
        uriComponents = uriComponentsBuilder.build();
      } else {
        uriComponents = uriComponentsBuilder.queryParam("teamId", optTeamId.get()).build();
      }

      // The external navigation service is proxied on behalf of a real user (their email is the
      // JWT subject) - unlike the Flow-internal navigation above, there is no sensible unscoped
      // answer with no current user (e.g. security disabled), so fail clearly rather than NPE.
      UserEntity currentUser = userService.getCurrentUser();
      if (currentUser == null) {
        throw new BoomerangException(BoomerangError.AUTH_REQUIRED);
      }
      HttpHeaders headers = new HttpHeaders();
      headers.add(
          AUTHORIZATION_HEADER, TOKEN_PREFIX + apiTokenService.createJWTToken(currentUser.getEmail()));
      HttpEntity<String> request = new HttpEntity<>(headers);
      ResponseEntity<List<Navigation>> response =
          restTemplate.exchange(
              uriComponents.toUriString(),
              HttpMethod.GET,
              request,
              new ParameterizedTypeReference<List<Navigation>>() {});
      return response.getBody();
    }
  }
}
