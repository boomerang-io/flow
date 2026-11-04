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

  private static Map<String, String> entry(String name, String... pairs) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.put("flow.security.tokens." + name + "." + pairs[i], pairs[i + 1]);
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
  void noEntriesBindsAnEmptyMap() {
    assertThat(bind(Map.of()).tokens()).isEmpty();
  }

  @Test
  void anEntryBindsUnderItsNameWithItsDefaults() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("dispatcher", "hash", HASH, "permissions", "workspace/read"))
            .tokens()
            .get("dispatcher");

    assertThat(entry.hash()).isEqualTo(HASH);
    assertThat(entry.actorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(entry.permissions()).containsExactly("workspace/read");
    assertThat(entry.expiresAt("k")).isNull();
  }

  @Test
  void environmentVariablesDeclareATokenByNameInTheVariable() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("FLOW_SECURITY_TOKENS_DISPATCHER_HASH", HASH);
    env.put("FLOW_SECURITY_TOKENS_DISPATCHER_ACTORKIND", "SERVICE");
    env.put(
        "FLOW_SECURITY_TOKENS_DISPATCHER_PERMISSIONS", "workspace/read,workflowrun/action");
    env.put("FLOW_SECURITY_TOKENS_DISPATCHER_DESCRIPTION", "the dispatcher");
    env.put("FLOW_SECURITY_TOKENS_DISPATCHER_EXPIRY", "2999-12-31");
    env.put("FLOW_SECURITY_TOKENS_BACKEND2_VALUE", VALUE);
    env.put("FLOW_SECURITY_TOKENS_BACKEND2_PERMISSIONS_0", "workspace/read");

    DeclaredTokenProperties properties =
        new Binder(
                ConfigurationPropertySources.from(
                    new SystemEnvironmentPropertySource("systemEnvironment", env)))
            .bindOrCreate("flow.security", DeclaredTokenProperties.class);

    assertThat(properties.tokens()).containsOnlyKeys("dispatcher", "backend2");
    DeclaredTokenProperties.Entry dispatcher = properties.tokens().get("dispatcher");
    assertThat(dispatcher.hash()).isEqualTo(HASH);
    assertThat(dispatcher.actorKind()).isEqualTo(TokenActorKind.SERVICE);
    assertThat(dispatcher.permissions()).containsExactly("workspace/read", "workflowrun/action");
    assertThat(dispatcher.description()).isEqualTo("the dispatcher");
    assertThat(dispatcher.expiresAt("k")).isEqualTo(Instant.parse("3000-01-01T00:00:00Z"));
    DeclaredTokenProperties.Entry backend = properties.tokens().get("backend2");
    assertThat(backend.value()).isEqualTo(VALUE);
    assertThat(backend.permissions()).containsExactly("workspace/read");
  }

  @Test
  void anInstantExpiryIsKeptAsGiven() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("a", "hash", HASH, "permissions", "workspace/read",
                "expiry", "2999-06-01T12:30:00Z"))
            .tokens()
            .get("a");

    assertThat(entry.expiresAt("k")).isEqualTo(Instant.parse("2999-06-01T12:30:00Z"));
  }

  @Test
  void anUppercaseHashIsStoredLowercase() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("a", "hash", HASH.toUpperCase(), "permissions", "workspace/read"))
            .tokens()
            .get("a");

    assertThat(entry.hash()).isEqualTo(HASH);
  }

  @Test
  void aNameOfAnythingButLowercaseLettersAndDigitsIsRefusedAndSaysWhy() {
    for (String name : List.of("Dispatcher", "my-token", "my_token", "x".repeat(64))) {
      assertThat(failureOf(entry(name, "hash", HASH, "permissions", "workspace/read")))
          .contains("flow.security.tokens." + name + ": the name must be 1 to 63 lowercase letters and digits only")
          .contains("does not survive an environment variable");
    }
  }

  @Test
  void bootstrapIsNotUsableAsAName() {
    assertThat(failureOf(entry("bootstrap", "hash", HASH, "permissions", "workspace/read")))
        .contains("'bootstrap' is not a usable name")
        .contains("FLOW_SECURITY_BOOTSTRAP_TOKEN");
  }

  @Test
  void exactlyOneOfHashAndValueIsRequired() {
    assertThat(failureOf(entry("a", "permissions", "workspace/read")))
        .contains("flow.security.tokens.a: set exactly one of hash or value");
    assertThat(
            failureOf(entry("a", "hash", HASH, "value", VALUE, "permissions", "workspace/read")))
        .contains("set exactly one of hash or value");
  }

  @Test
  void aHashThatIsNotASha256HexIsRefused() {
    assertThat(failureOf(entry("a", "hash", "abc123", "permissions", "workspace/read")))
        .contains("flow.security.tokens.a.hash must be the 64-character hex SHA-256");
  }

  @Test
  void aValueWeakerThanTheBootstrapTokenIsRefused() {
    assertThat(failureOf(entry("a", "value", "bfg_short", "permissions", "workspace/read")))
        .contains("flow.security.tokens.a.value must be bfg_ followed by at least 32 characters");
    assertThat(
            failureOf(
                entry("a", "value", "bfk_" + "a".repeat(40), "permissions", "workspace/read")))
        .contains("flow.security.tokens.a.value must be bfg_ followed by at least 32 characters");
  }

  @Test
  void anActorKindThatDoesNotExistIsRefused() {
    assertThat(
            failureOf(
                entry("a", "hash", HASH, "actor-kind", "ROBOT", "permissions", "workspace/read")))
        .contains("ROBOT");
  }

  @Test
  void permissionsMayBeLeftOutAndTheEntryHoldsNone() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("dispatcher", "hash", HASH)).tokens().get("dispatcher");

    assertThat(entry.permissions()).isEmpty();
  }

  @Test
  void anyPermissionGivenMustStillBeAResourceActionGrant() {
    for (String bad : List.of("workspace", "nothing/read", "workspace/destroy", "workspace/read/x")) {
      assertThat(failureOf(entry("a", "hash", HASH, "permissions", bad)))
          .contains("flow.security.tokens.a.permissions must each be <resource>/<action>");
    }
    assertThat(failureOf(entry("a", "hash", HASH, "permissions", "workspace/read,nothing/read")))
        .contains("flow.security.tokens.a.permissions must each be <resource>/<action>");
  }

  @Test
  void aPastExpiryStillBindsSoTheStartGoesAhead() {
    DeclaredTokenProperties.Entry entry =
        bind(entry("a", "hash", HASH, "permissions", "workspace/read", "expiry", "2020-01-01"))
            .tokens()
            .get("a");

    assertThat(entry.expiresAt("k")).isEqualTo(Instant.parse("2020-01-02T00:00:00Z"));
  }

  @Test
  void anExpiryThatIsNotADateIsRefused() {
    assertThat(
            failureOf(
                entry("a", "hash", HASH, "permissions", "workspace/read", "expiry", "soon")))
        .contains("flow.security.tokens.a.expiry must be an ISO-8601 instant or date");
  }
}
