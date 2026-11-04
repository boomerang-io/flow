package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.core.security.enums.TokenActorKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * A declared token's entries bind from properties and from environment variables, and an invalid
 * entry stops the binding with a message that names it.
 */
class DeclaredTokenPropertiesTest {

  private static final String VALUE = "bfg_" + "a".repeat(40);
  private static final String HASH = "a".repeat(64);

  private static DeclaredTokenProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate("flow.security", DeclaredTokenProperties.class);
  }

  private static Map<String, String> entry(String... pairs) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.put("flow.security.tokens[0]." + pairs[i], pairs[i + 1]);
    }
    return properties;
  }

  private static String failureOf(Map<String, String> properties) {
    Throwable thrown = null;
    try {
      bind(properties);
    } catch (BindException e) {
      thrown = e;
    }
    assertThat(thrown).as("binding should fail").isNotNull();
    StringBuilder messages = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }

  @Test
  void noEntriesBindsAnEmptyList() {
    assertThat(bind(Map.of()).tokens()).isEmpty();
  }

  @Test
  void anEntryBindsWithItsDefaults() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("name", "dispatcher", "hash", HASH, "permissions", "workspace/read"))
            .tokens()
            .get(0);

    assertThat(entry.name()).isEqualTo("dispatcher");
    assertThat(entry.hash()).isEqualTo(HASH);
    assertThat(entry.actorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(entry.permissions()).containsExactly("workspace/read");
    assertThat(entry.expiresAt()).isNull();
  }

  @Test
  void environmentVariablesBindTheSameList() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("FLOW_SECURITY_TOKENS_0_NAME", "dispatcher");
    env.put("FLOW_SECURITY_TOKENS_0_HASH", HASH);
    env.put("FLOW_SECURITY_TOKENS_0_ACTORKIND", "SERVICE");
    env.put("FLOW_SECURITY_TOKENS_0_PERMISSIONS", "workspace/read,workflowrun/write");
    env.put("FLOW_SECURITY_TOKENS_0_DESCRIPTION", "the dispatcher");
    env.put("FLOW_SECURITY_TOKENS_0_EXPIRY", "2999-12-31");
    env.put("FLOW_SECURITY_TOKENS_1_NAME", "backend");
    env.put("FLOW_SECURITY_TOKENS_1_VALUE", VALUE);
    env.put("FLOW_SECURITY_TOKENS_1_PERMISSIONS_0", "workspace/read");

    DeclaredTokenProperties properties =
        new Binder(
                ConfigurationPropertySources.from(
                    new SystemEnvironmentPropertySource("systemEnvironment", env)))
            .bindOrCreate("flow.security", DeclaredTokenProperties.class);

    assertThat(properties.tokens()).hasSize(2);
    DeclaredTokenProperties.Entry first = properties.tokens().get(0);
    assertThat(first.name()).isEqualTo("dispatcher");
    assertThat(first.hash()).isEqualTo(HASH);
    assertThat(first.permissions()).containsExactly("workspace/read", "workflowrun/write");
    assertThat(first.description()).isEqualTo("the dispatcher");
    assertThat(first.expiresAt()).isEqualTo(Instant.parse("3000-01-01T00:00:00Z"));
    DeclaredTokenProperties.Entry second = properties.tokens().get(1);
    assertThat(second.value()).isEqualTo(VALUE);
    assertThat(second.permissions()).containsExactly("workspace/read");
  }

  @Test
  void anInstantExpiryIsKeptAsGiven() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("name", "a", "hash", HASH, "permissions", "workspace/read",
                "expiry", "2999-06-01T12:30:00Z"))
            .tokens()
            .get(0);

    assertThat(entry.expiresAt()).isEqualTo(Instant.parse("2999-06-01T12:30:00Z"));
  }

  @Test
  void anUppercaseHashIsStoredLowercase() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("name", "a", "hash", HASH.toUpperCase(), "permissions", "workspace/read"))
            .tokens()
            .get(0);

    assertThat(entry.hash()).isEqualTo(HASH);
  }

  @Test
  void aNameThatIsNotALowercaseSlugIsRefused() {
    for (String name : List.of("Dispatcher", "has space", "-lead", "trail-", "under_score")) {
      assertThat(failureOf(entry("name", name, "hash", HASH, "permissions", "workspace/read")))
          .contains("must be a lowercase slug");
    }
  }

  @Test
  void aNameDeclaredTwiceIsRefused() {
    Map<String, String> properties = entry("name", "a", "hash", HASH, "permissions", "workspace/read");
    properties.put("flow.security.tokens[1].name", "a");
    properties.put("flow.security.tokens[1].hash", "b".repeat(64));
    properties.put("flow.security.tokens[1].permissions", "workspace/read");

    assertThat(failureOf(properties)).contains("the name 'a' is declared twice");
  }

  @Test
  void exactlyOneOfHashAndValueIsRequired() {
    assertThat(failureOf(entry("name", "a", "permissions", "workspace/read")))
        .contains("set exactly one of hash or value");
    assertThat(
            failureOf(
                entry("name", "a", "hash", HASH, "value", VALUE, "permissions", "workspace/read")))
        .contains("set exactly one of hash or value");
  }

  @Test
  void aHashThatIsNotASha256HexIsRefused() {
    assertThat(failureOf(entry("name", "a", "hash", "abc123", "permissions", "workspace/read")))
        .contains("[a].hash must be the 64-character hex SHA-256");
  }

  @Test
  void aValueWeakerThanTheBootstrapTokenIsRefused() {
    assertThat(failureOf(entry("name", "a", "value", "bfg_short", "permissions", "workspace/read")))
        .contains("[a].value must be bfg_ followed by at least 32 characters");
    assertThat(
            failureOf(
                entry("name", "a", "value", "bfk_" + "a".repeat(40), "permissions", "workspace/read")))
        .contains("[a].value must be bfg_ followed by at least 32 characters");
  }

  @Test
  void anActorKindThatDoesNotExistIsRefused() {
    assertThat(
            failureOf(
                entry("name", "a", "hash", HASH, "actor-kind", "ROBOT", "permissions", "workspace/read")))
        .contains("ROBOT");
  }

  @Test
  void permissionsAreRequiredAndMustBeResourceActionGrants() {
    assertThat(failureOf(entry("name", "a", "hash", HASH))).contains("[a].permissions must name");
    for (String bad : List.of("workspace", "nothing/read", "workspace/destroy", "workspace/read/x")) {
      assertThat(failureOf(entry("name", "a", "hash", HASH, "permissions", bad)))
          .contains("[a].permissions must each be <resource>/<action>");
    }
  }

  @Test
  void aPastExpiryStillBindsSoTheStartGoesAhead() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("name", "a", "hash", HASH, "permissions", "workspace/read", "expiry", "2020-01-01"))
            .tokens()
            .get(0);

    assertThat(entry.expiresAt()).isEqualTo(Instant.parse("2020-01-02T00:00:00Z"));
  }

  @Test
  void anExpiryThatIsNotADateIsRefused() {
    assertThat(
            failureOf(
                entry("name", "a", "hash", HASH, "permissions", "workspace/read", "expiry", "soon")))
        .contains("[a].expiry must be an ISO-8601 instant or date");
  }
}
