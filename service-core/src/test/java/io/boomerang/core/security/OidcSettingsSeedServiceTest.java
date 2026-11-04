package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.SettingsService;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The OpenID Connect properties fill the auth settings only while they are empty, never overwrite a
 * stored value, refuse an issuer that is not https (loopback excepted), and show in the
 * webapp-facing auth config straight away.
 */
class OidcSettingsSeedServiceTest extends AbstractEngineIntegrationTest {

  private static final String ISSUER = "https://idp.example.test";
  private static final String CLIENT_ID = "flow-web";
  private static final String AUTH_CONFIG = "/internal/v1/auth/config";

  @Autowired private SettingsService settingsService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    store("", "");
  }

  @AfterEach
  void emptyTheSettings() {
    store("", "");
  }

  private OidcSettingsSeedService seeder(String issuer, String clientId) {
    return new OidcSettingsSeedService(issuer, clientId, settingsService);
  }

  private void store(String issuer, String clientId) {
    SettingEntity auth = settingsService.getSettingByKey("auth");
    auth.getConfig().stream()
        .filter(c -> "oidc.issuer".equals(c.getKey()))
        .forEach(c -> c.setValue(issuer));
    auth.getConfig().stream()
        .filter(c -> "oidc.clientId".equals(c.getKey()))
        .forEach(c -> c.setValue(clientId));
    settingsService.updateSetting(auth);
  }

  private String issuer() {
    return settingsService.getSettingConfig("auth", "oidc.issuer").getValue();
  }

  private String clientId() {
    return settingsService.getSettingConfig("auth", "oidc.clientId").getValue();
  }

  @Test
  void unsetPropertiesChangeNothing() {
    seeder("", "").seed();
    seeder(null, "  ").seed();

    assertThat(issuer()).isEmpty();
    assertThat(clientId()).isEmpty();
  }

  @Test
  void setPropertiesFillEmptySettingsAndShowInTheAuthConfigAtOnce() throws Exception {
    mockMvc.perform(get(AUTH_CONFIG).servletPath(AUTH_CONFIG)).andExpect(jsonPath("$.mode").value("proxy"));

    seeder(ISSUER, CLIENT_ID).seed();

    assertThat(issuer()).isEqualTo(ISSUER);
    assertThat(clientId()).isEqualTo(CLIENT_ID);
    mockMvc
        .perform(get(AUTH_CONFIG).servletPath(AUTH_CONFIG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("oidc"))
        .andExpect(jsonPath("$.issuer").value(ISSUER))
        .andExpect(jsonPath("$.clientId").value(CLIENT_ID));
  }

  @Test
  void aStoredValueIsKeptAndTheOtherSettingIsStillSeeded() {
    store("https://admin.example.test", "");

    seeder(ISSUER, CLIENT_ID).seed();

    assertThat(issuer()).isEqualTo("https://admin.example.test");
    assertThat(clientId()).isEqualTo(CLIENT_ID);
  }

  @Test
  void aSecondStartDoesNotOverwriteWhatTheFirstSeeded() {
    seeder(ISSUER, CLIENT_ID).seed();

    seeder("https://other.example.test", "other").seed();

    assertThat(issuer()).isEqualTo(ISSUER);
    assertThat(clientId()).isEqualTo(CLIENT_ID);
  }

  @Test
  void anIssuerThatIsNotHttpsStopsTheStartAndSeedsNothing() {
    for (String bad :
        new String[] {
          "http://idp.example.test",
          "idp.example.test",
          "ftp://idp.example.test",
          "https://",
          "https://user:pw@idp.example.test",
          "not a url"
        }) {
      assertThatThrownBy(() -> seeder(bad, CLIENT_ID).seed())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("flow.auth.oidc.issuer must be an absolute https URL");
    }
    assertThat(issuer()).isEmpty();
    assertThat(clientId()).isEmpty();
  }

  @Test
  void aLoopbackIssuerMayUseHttp() {
    seeder("http://idp.localhost:4380", "flow").seed();

    assertThat(issuer()).isEqualTo("http://idp.localhost:4380");
  }
}
