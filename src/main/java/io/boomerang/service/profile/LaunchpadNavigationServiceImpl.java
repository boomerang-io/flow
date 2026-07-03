package io.boomerang.service.profile;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import io.boomerang.model.profile.Features;
import io.boomerang.model.profile.Navigation;
import io.boomerang.model.profile.NavigationResponse;
import io.boomerang.model.profile.Platform;
import io.boomerang.mongo.service.FlowSettingsService;
import io.boomerang.security.model.UserToken;
import io.boomerang.security.service.ApiTokenService;
import io.boomerang.service.UserIdentityService;

@Service
public class LaunchpadNavigationServiceImpl implements LaunchpadNavigationService {

  @Value("${core.feature.notifications.enable}")
  private Boolean enableFeatureNotification;

  @Value("${core.feature.docs.enable}")
  private boolean enableDocs;

  @Value("${core.feature.support.enable}")
  private Boolean enableSupport;

  @Value("${core.platform.name}")
  private String platformName;

  @Value("${boomerang.signOutUrl}")
  private String platformSignOutUrl;

  @Value("${boomerang.baseUrl}")
  private String platformBaseUrl;

  @Value("${boomerang.version}")
  private String platformVersion;
  
  @Autowired
  @Qualifier("internalRestTemplate")
  private RestTemplate restTemplate;
  
  @Value("${flow.externalUrl.platformNavigation}")
  private String platformNavigationUrl;

  @Autowired
  private FlowSettingsService settingsService;
  
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";

  @Autowired
  private ApiTokenService apiTokenService;
  
  @Autowired
  private UserIdentityService identityService;
  
  @Override
  public NavigationResponse getLaunchpadNavigation(boolean isUserAdmin) {
    
    UserToken userToken = identityService.getUserDetails();
    if (userToken == null) {
      return null;
    }
    String email = userToken.getEmail();
    
    if (platformNavigationUrl.isBlank()) {
      return getFlowNavigationResponse();
    }
    else {
      return getExternalNavigationResponse(email);
    }
  }

  private NavigationResponse getFlowNavigationResponse() {
    final List<Navigation> navList = new ArrayList<>();
    NavigationResponse navigationResponse = new NavigationResponse();
    navigationResponse.setNavigation(navList);

    Features features = new Features();
    features.setNotificationsEnabled(false);
    features.setDocsEnabled(false);
    features.setSupportEnabled(false);
    features.setConsentEnabled(false);
    
    navigationResponse.setFeatures(features);

    String appName = settingsService.getConfiguration("customizations", "appName").getValue();
    String platformName = settingsService.getConfiguration("customizations", "platformName").getValue();
    String displayLogo = settingsService.getConfiguration("customizations", "displayLogo").getValue();
    String logoURL = settingsService.getConfiguration("customizations", "logoURL").getValue();
    String name = platformName + " " + appName;
    Platform platform = new Platform();
    platform.setName(name.trim());
    platform.setVersion(platformVersion);
    platform.setSignOutUrl(platformBaseUrl + platformSignOutUrl);
    platform.setAppName(appName);
    platform.setPlatformName(platformName);
    platform.setDisplayLogo(Boolean.valueOf(displayLogo));
    platform.setLogoURL(logoURL);
    platform.setPrivateTeams(false);
    platform.setSendMail(false);
    navigationResponse.setPlatform(platform);

    return navigationResponse;
  }
  
  private NavigationResponse getExternalNavigationResponse(String email) {
    UriComponents uriComponents = UriComponentsBuilder.fromUriString(platformNavigationUrl).build();
    HttpHeaders headers = buildHeaders(email);
    HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);
    ResponseEntity<NavigationResponse> response =
        restTemplate.exchange(uriComponents.toUriString(), HttpMethod.GET, requestUpdate, NavigationResponse.class);
    return response.getBody();
  }
  
  private HttpHeaders buildHeaders(String email) {
    final HttpHeaders headers = new HttpHeaders();
    headers.add("Accept", "application/json");
    headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + apiTokenService.createJWTToken(email));
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
