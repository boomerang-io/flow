package io.boomerang.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import io.boomerang.client.model.UserProfile;
import io.boomerang.security.service.ApiTokenService;
import io.boomerang.service.UserIdentityService;

@Service
public class ExternalUserServiceImpl implements ExternalUserService {

  @Value("${flow.externalUrl.user}")
  private String externalUserUrl;

  @Autowired
  @Qualifier("internalRestTemplate")
  private RestTemplate restTemplate;
  
  @Autowired
  private UserIdentityService userDetailsService;

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";

  @Autowired
  private ApiTokenService apiTokenService;

  @Override
  public UserProfile getInternalUserProfile() {
    try {
      String userEmail = userDetailsService.getUserDetails().getEmail();
      UriComponents uriComponents = UriComponentsBuilder.fromUriString(externalUserUrl).
          queryParam("userEmail", userEmail).build();
      HttpHeaders headers = buildHeaders(userEmail);
    
      HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);
      ResponseEntity<UserProfile> response =
          restTemplate.exchange(uriComponents.toUriString(), HttpMethod.GET, requestUpdate, UserProfile.class);
      return response.getBody();
    } catch (RestClientException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public UserProfile getUserProfileById(String id) {
    UriComponents uriComponents = UriComponentsBuilder.fromUriString(externalUserUrl).
        queryParam("userId", id).build();
    HttpHeaders headers = buildHeaders(null);
  
    HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);
    ResponseEntity<UserProfile> response =
        restTemplate.exchange(uriComponents.toUriString(), HttpMethod.GET, requestUpdate, UserProfile.class);
    return response.getBody();
  }
  
  private HttpHeaders buildHeaders(String email) {

    final HttpHeaders headers = new HttpHeaders();
    headers.add("Accept", "application/json");
    
    if (email != null) {
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + apiTokenService.createJWTToken(email));
    } else {
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + apiTokenService.createJWTToken());
    }
    
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

}
