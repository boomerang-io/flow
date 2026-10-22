package io.boomerang.core;

import io.boomerang.core.model.ExternalUserProfile;
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

@Service
public class ExternalUserService {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";

  @Value("${flow.externalUrl.user}")
  private String externalUserUrl;

  private final RestTemplate restTemplate;
  private final ExternalTokenService externalTokenService;

  public ExternalUserService(
      @Qualifier("internalRestTemplate") RestTemplate restTemplate,
      ExternalTokenService externalTokenService) {
    this.restTemplate = restTemplate;
    this.externalTokenService = externalTokenService;
  }

  public ExternalUserProfile getUserProfileByEmail(String email) {
    try {
      UriComponents uriComponents =
          UriComponentsBuilder.fromHttpUrl(externalUserUrl).queryParam("userEmail", email).build();
      HttpHeaders headers = buildHeaders(email);

      HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);
      ResponseEntity<ExternalUserProfile> response =
          restTemplate.exchange(
              uriComponents.toUriString(),
              HttpMethod.GET,
              requestUpdate,
              ExternalUserProfile.class);
      return response.getBody();
    } catch (RestClientException e) {
      e.printStackTrace();
      return null;
    }
  }

  public ExternalUserProfile getUserProfileById(String id) {
    UriComponents uriComponents =
        UriComponentsBuilder.fromHttpUrl(externalUserUrl).queryParam("userId", id).build();
    HttpHeaders headers = buildHeaders(null);

    HttpEntity<String> requestUpdate = new HttpEntity<>("", headers);
    ResponseEntity<ExternalUserProfile> response =
        restTemplate.exchange(
            uriComponents.toUriString(), HttpMethod.GET, requestUpdate, ExternalUserProfile.class);
    return response.getBody();
  }

  private HttpHeaders buildHeaders(String email) {

    final HttpHeaders headers = new HttpHeaders();
    headers.add("Accept", "application/json");

    if (email != null) {
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + externalTokenService.createJWTToken(email));
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + externalTokenService.createJWTToken(email));
    } else {
      headers.add(AUTHORIZATION_HEADER, TOKEN_PREFIX + externalTokenService.createJWTToken());
    }

    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
