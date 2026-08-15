package io.boomerang.core;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.core.model.ExternalUserProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

/*
 * Uplifted from the v3-era BoomerangUserServiceTests — now a plain unit test with a
 * MockRestServiceServer instead of a full Spring context with embedded Mongo.
 */
@ExtendWith(MockitoExtension.class)
class ExternalUserServiceTests {

  @Mock private ExternalTokenService externalTokenService;

  private ExternalUserService userService;
  private MockRestServiceServer mockServer;

  @BeforeEach
  void setUp() throws IOException {
    RestTemplate restTemplate = new RestTemplate();
    userService = new ExternalUserService(restTemplate, externalTokenService);
    ReflectionTestUtils.setField(
        userService, "externalUserUrl", "http://localhost:8084/internal/users/user");
    mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    mockServer
        .expect(requestTo(containsString("http://localhost:8084/internal/users/user")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(getMockFile("mock/users/users.json"), MediaType.APPLICATION_JSON));
  }

  @Test
  void testGetUserProfileByEmail() {
    when(externalTokenService.createJWTToken(anyString())).thenReturn("jwt-token");
    ExternalUserProfile userProfile = userService.getUserProfileByEmail("trbula@us.ibm.com");
    assertEquals("trbula@us.ibm.com", userProfile.getEmail());
  }

  private String getMockFile(String path) throws IOException {
    return StreamUtils.copyToString(
        new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
  }
}
