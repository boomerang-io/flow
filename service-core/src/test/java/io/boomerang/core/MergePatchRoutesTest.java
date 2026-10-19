package io.boomerang.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.ParameterService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The settings and global parameter routes take a JSON Merge Patch over HTTP, under either media
 * type, and the full-replace PUT they replaced is no longer served.
 */
class MergePatchRoutesTest extends AbstractEngineIntegrationTest {

  private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private ParameterService parameterService;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String bearer;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    try {
      TokenCreateRequest request = new TokenCreateRequest();
      request.setType(AuthScope.global);
      request.setName("merge-patch-routes-" + UUID.randomUUID());
      request.setPermissions(List.of("**/**"));
      bearer = "Bearer " + tokenService.create(request).getToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void aGlobalParamIsPatchedUnderEitherMediaTypeAndTheResponseIsMasked() throws Exception {
    String name = "routes" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("password");
    param.setValue("route-secret-0001");
    parameterService.create(param);
    SecurityContextHolder.clearContext();

    for (MediaType type : List.of(MERGE_PATCH, MediaType.APPLICATION_JSON)) {
      mockMvc
          .perform(
              patch("/api/v2/parameters/" + name)
                  .header(HttpHeaders.AUTHORIZATION, bearer)
                  .contentType(type)
                  .content("{\"description\":\"" + type + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.description").value(type.toString()))
          .andExpect(jsonPath("$.value").value("*****"));
    }

    mockMvc
        .perform(
            patch("/api/v2/parameters/no-such-param-" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MERGE_PATCH)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void anUnknownSettingsKeyIsABadRequestNamingIt() throws Exception {
    mockMvc
        .perform(
            patch("/api/v2/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MERGE_PATCH)
                .content("{\"no-such-group\":{\"a\":\"b\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1030))
        .andExpect(jsonPath("$.reason").value("SETTINGS_INVALID_KEY"));
  }

  @Test
  void theFullReplacePutRoutesAreGone() throws Exception {
    mockMvc
        .perform(
            put("/api/v2/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
        .andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(
            put("/api/v2/parameters")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
  }
}
