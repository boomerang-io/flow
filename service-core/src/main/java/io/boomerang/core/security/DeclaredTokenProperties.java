package io.boomerang.core.security;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.security.enums.TokenActorKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Machine tokens declared in configuration ({@code flow.security.tokens[n]}), registered at start by
 * {@link DeclaredTokenService}. Each entry carries exactly one of {@code hash} (the SHA-256 of the
 * raw token as hex, so the environment never holds the plaintext) or {@code value} (a {@code bfg_}
 * token of at least 32 further characters). An invalid entry fails the binding, so the start stops
 * before the install serves.
 */
@ConfigurationProperties(prefix = "flow.security")
public record DeclaredTokenProperties(@DefaultValue List<Entry> tokens) {

  private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");
  private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{64}$");

  public DeclaredTokenProperties {
    Set<String> names = new HashSet<>();
    tokens.forEach(
        t ->
            Assert.isTrue(
                names.add(t.name()),
                "flow.security.tokens: the name '" + t.name() + "' is declared twice"));
  }

  /**
   * One declared token. {@code expiry} is an ISO-8601 instant, or a date meaning the end of that day
   * in UTC; once it passes the token stops authenticating, like any expired token, and the start
   * still goes ahead. {@code permissions} are global grants in the shape {@code
   * POST /api/v2/token} takes.
   */
  public record Entry(
      String name,
      String hash,
      String value,
      @DefaultValue("SERVICE") TokenActorKind actorKind,
      @DefaultValue List<String> permissions,
      String description,
      String expiry) {

    public Entry {
      Assert.isTrue(
          StringUtils.hasText(name) && NAME.matcher(name).matches(),
          "flow.security.tokens: the name '" + name + "' must be a lowercase slug (a-z, 0-9, -)");
      String key = "flow.security.tokens[" + name + "]";
      Assert.isTrue(
          StringUtils.hasText(hash) != StringUtils.hasText(value),
          key + ": set exactly one of hash or value");
      Assert.isTrue(
          !StringUtils.hasText(hash) || HASH.matcher(hash).matches(),
          key + ".hash must be the 64-character hex SHA-256 of the raw token");
      Assert.isTrue(
          !StringUtils.hasText(value)
              || value.matches(BootstrapTokenService.BOOTSTRAP_TOKEN_PATTERN),
          key + ".value must be bfg_ followed by at least 32 characters");
      hash = StringUtils.hasText(hash) ? hash.toLowerCase() : null;
      Assert.notEmpty(permissions, key + ".permissions must name at least one grant");
      try {
        TokenService.assertValidPermissions(permissions);
      } catch (BoomerangException e) {
        throw new IllegalArgumentException(
            key + ".permissions must each be <resource>/<action>, for example workspace/read", e);
      }
      parse(key, expiry);
    }

    /** The moment the token stops working, or null when it does not expire. */
    public Instant expiresAt() {
      return parse("flow.security.tokens[" + name + "]", expiry);
    }

    private static Instant parse(String key, String expiry) {
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
