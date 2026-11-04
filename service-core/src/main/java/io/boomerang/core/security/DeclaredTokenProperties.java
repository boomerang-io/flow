package io.boomerang.core.security;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.security.enums.TokenActorKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Machine tokens declared in configuration ({@code flow.security.tokens.<name>.*}), registered at
 * start by {@link DeclaredTokenService}. Each entry carries exactly one of {@code hash} (the SHA-256
 * of the raw token as hex, so the environment never holds the plaintext) or {@code value} (a {@code
 * bfg_} token of at least 32 further characters). An invalid entry fails the binding, so the start
 * stops before the install serves. {@code permissions} may be left out: the token then holds no
 * grant and reaches only routes that need no permission, which is the dispatcher protocol.
 *
 * <p>A name is lowercase letters and digits only: in an environment variable an underscore becomes a
 * dot, so a hyphen or underscore in a name cannot survive {@code FLOW_SECURITY_TOKENS_<NAME>_HASH}.
 */
@ConfigurationProperties(prefix = "flow.security")
public record DeclaredTokenProperties(@DefaultValue Map<String, Entry> tokens) {

  private static final Pattern NAME = Pattern.compile("^[a-z0-9]{1,63}$");
  private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{64}$");

  public DeclaredTokenProperties {
    tokens.forEach(DeclaredTokenProperties::validate);
  }

  private static void validate(String name, Entry entry) {
    String key = "flow.security.tokens." + name;
    Assert.isTrue(
        !"bootstrap".equals(name),
        key + ": 'bootstrap' is not a usable name; the bootstrap token is set with"
            + " FLOW_SECURITY_BOOTSTRAP_TOKEN");
    Assert.isTrue(
        NAME.matcher(name).matches(),
        key
            + ": the name must be 1 to 63 lowercase letters and digits only, because a hyphen or"
            + " underscore does not survive an environment variable");
    Assert.isTrue(
        StringUtils.hasText(entry.hash()) != StringUtils.hasText(entry.value()),
        key + ": set exactly one of hash or value");
    Assert.isTrue(
        !StringUtils.hasText(entry.hash()) || HASH.matcher(entry.hash()).matches(),
        key + ".hash must be the 64-character hex SHA-256 of the raw token");
    Assert.isTrue(
        !StringUtils.hasText(entry.value())
            || entry.value().matches(BootstrapTokenService.BOOTSTRAP_TOKEN_PATTERN),
        key + ".value must be bfg_ followed by at least 32 characters");
    try {
      TokenService.assertValidPermissions(entry.permissions());
    } catch (BoomerangException e) {
      throw new IllegalArgumentException(
          key + ".permissions must each be <resource>/<action>, for example workspace/read", e);
    }
    entry.expiresAt(key);
  }

  /**
   * One declared token. {@code expiry} is an ISO-8601 instant, or a date meaning the end of that day
   * in UTC; once it passes the token stops authenticating, like any expired token, and the start
   * still goes ahead. {@code permissions} are global grants in the shape {@code
   * POST /api/v2/token} takes; none is allowed.
   */
  public record Entry(
      String hash,
      String value,
      @DefaultValue("SERVICE") TokenActorKind actorKind,
      @DefaultValue List<String> permissions,
      String description,
      String expiry) {

    public Entry {
      hash = StringUtils.hasText(hash) ? hash.toLowerCase() : null;
    }

    /** The moment the token stops working, or null when it does not expire. */
    public Instant expiresAt(String key) {
      if (!StringUtils.hasText(expiry)) {
        return null;
      }
      try {
        return Instant.parse(expiry.trim());
      } catch (DateTimeParseException notAnInstant) {
        try {
          return LocalDate.parse(expiry.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
          throw new IllegalArgumentException(
              key + ".expiry must be an ISO-8601 instant or date", e);
        }
      }
    }
  }
}
