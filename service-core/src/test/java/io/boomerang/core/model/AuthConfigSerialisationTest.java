package io.boomerang.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The exact wire shape of {@code GET /api/v2/auth/config} - the webapp's sign-in bootstrap is
 * built against these literal field names and lowercase mode values, and {@code issuer}/{@code
 * clientId} must be ABSENT (not null) outside oidc mode.
 */
class AuthConfigSerialisationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void oidcSerialisesModeIssuerAndClientId() {
    String json = mapper.writeValueAsString(AuthConfig.oidc("https://idp.example.test", "flow-web"));

    assertThat(json)
        .isEqualTo("{\"mode\":\"oidc\",\"issuer\":\"https://idp.example.test\",\"clientId\":\"flow-web\"}");
  }

  @Test
  void proxySerialisesTheModeAndItsSignOutUrl() {
    assertThat(mapper.writeValueAsString(AuthConfig.proxy("https://sso.example.test/pkmslogout")))
        .isEqualTo(
            "{\"mode\":\"proxy\",\"signOutUrl\":\"https://sso.example.test/pkmslogout\"}");
  }

  @Test
  void proxyWithoutASignOutUrlSerialisesOnlyTheMode() {
    // Blank and null both collapse to absent - the webapp falls back to landing on the root.
    assertThat(mapper.writeValueAsString(AuthConfig.proxy(null))).isEqualTo("{\"mode\":\"proxy\"}");
    assertThat(mapper.writeValueAsString(AuthConfig.proxy("  "))).isEqualTo("{\"mode\":\"proxy\"}");
  }

  @Test
  void noneSerialisesOnlyTheMode() {
    assertThat(mapper.writeValueAsString(AuthConfig.none())).isEqualTo("{\"mode\":\"none\"}");
  }
}
