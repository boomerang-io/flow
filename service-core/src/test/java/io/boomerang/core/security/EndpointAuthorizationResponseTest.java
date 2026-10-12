package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the status a caller receives from a real endpoint, sent through the real security filter
 * chain: {@link AuthCriteriaAuthorizationManagerTest} pins only what each check raises, and {@link
 * MethodSecurityConfigurationTest} only that a test bean is wrapped. Nothing else proves that the
 * product's own controllers are wrapped, or what a denial becomes once it leaves the controller.
 */
class EndpointAuthorizationResponseTest extends AbstractEngineIntegrationTest {

  // Needs system/read; a session, user or global token may call it.
  private static final String FEATURES = "/api/v2/features";
  // A session or user token only: a global token is the wrong kind, whatever it holds.
  private static final String PROFILE = "/api/v2/profile";

  @Autowired private WebApplicationContext context;

  @Autowired private TokenService tokenService;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  @Test
  void aCallerHoldingThePermissionReachesTheEndpoint() throws Exception {
    String bearer = mintGlobalToken("**/**");

    mockMvc
        .perform(get(FEATURES).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
        .andExpect(status().isOk());
  }

  @Test
  void aCallerMissingThePermissionIsForbidden() throws Exception {
    String bearer = mintGlobalToken("workflow/read");
    double deniedBefore = denied(PermissionResource.SYSTEM, PermissionAction.READ);

    mockMvc
        .perform(get(FEATURES).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
        .andExpect(status().isForbidden());

    // The denial is the endpoint's own check, not some other refusal further out.
    assertThat(denied(PermissionResource.SYSTEM, PermissionAction.READ))
        .isEqualTo(deniedBefore + 1);
  }

  @Test
  void aTokenKindTheEndpointDoesNotAcceptIsUnauthorized() throws Exception {
    String bearer = mintGlobalToken("**/**");
    double deniedBefore = denied(PermissionResource.USER, PermissionAction.READ);

    mockMvc
        .perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value(1003))
        .andExpect(jsonPath("$.reason").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.status").value("401 UNAUTHORIZED"));

    assertThat(denied(PermissionResource.USER, PermissionAction.READ)).isEqualTo(deniedBefore + 1);
  }

  @Test
  void aCallerWithNoCredentialsIsUnauthorized() throws Exception {
    SecurityContextHolder.clearContext();

    mockMvc
        .perform(get(FEATURES))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value(1003))
        .andExpect(jsonPath("$.reason").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.status").value("401 UNAUTHORIZED"));
  }

  /**
   * Mints a global token holding one permission, as the global identity the base class
   * establishes, then clears that identity so the request carries only its own bearer.
   */
  private String mintGlobalToken(String permission) {
    try {
      TokenCreateRequest request = new TokenCreateRequest();
      request.setType(AuthScope.global);
      request.setName("endpoint-authorization-" + UUID.randomUUID());
      request.setPermissions(List.of(permission));
      return tokenService.create(request).getToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private double denied(PermissionResource resource, PermissionAction action) {
    return meterRegistry
        .counter(
            "flow.security.denied",
            "resource",
            resource.getLabel(),
            "action",
            action.getLabel(),
            "type",
            AuthScope.global.toString())
        .count();
  }
}
