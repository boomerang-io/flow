package io.boomerang.core.security;

import io.boomerang.core.SettingsService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Seeds the {@code auth} settings' OpenID Connect issuer and client id from {@code
 * flow.auth.oidc.issuer} and {@code flow.auth.oidc.client-id}, so a fresh install can sign people
 * in without editing the database. Each value is written only while the stored one is empty, as one
 * conditional update: an admin's value, and the first writer among instances starting together,
 * always win. Blank properties change nothing.
 */
@Service
public class OidcSettingsSeedService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String GROUP = "auth";
  private static final String ISSUER_KEY = "oidc.issuer";
  private static final String CLIENT_ID_KEY = "oidc.clientId";

  private final String issuer;
  private final String clientId;
  private final SettingsService settingsService;

  public OidcSettingsSeedService(
      @Value("${flow.auth.oidc.issuer:}") String issuer,
      @Value("${flow.auth.oidc.client-id:}") String clientId,
      SettingsService settingsService) {
    this.issuer = issuer == null ? "" : issuer.trim();
    this.clientId = clientId == null ? "" : clientId.trim();
    this.settingsService = settingsService;
  }

  /**
   * Seed each property that is set into its empty setting. Throw when the issuer is not an
   * absolute https URL (http is allowed for a loopback host), so a mistyped issuer stops the start
   * rather than trusting an unintended one.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    if (StringUtils.hasText(issuer)) {
      requireTrustworthyIssuer(issuer);
      seedIfEmpty(ISSUER_KEY, "flow.auth.oidc.issuer", issuer);
    }
    if (StringUtils.hasText(clientId)) {
      seedIfEmpty(CLIENT_ID_KEY, "flow.auth.oidc.client-id", clientId);
    }
  }

  private void seedIfEmpty(String key, String property, String value) {
    if (settingsService.setIfEmpty(GROUP, key, value)) {
      LOGGER.info("Seeded the {} setting from {}.", key, property);
      return;
    }
    String stored = storedValue(key);
    if (stored != null && !stored.equals(value)) {
      LOGGER.info("The stored {} setting is kept; {} does not overwrite it.", key, property);
    }
  }

  private String storedValue(String key) {
    try {
      return settingsService.getSettingConfig(GROUP, key).getValue();
    } catch (RuntimeException e) {
      LOGGER.warn("The {} setting could not be read after seeding: {}", key, e.getMessage());
      return null;
    }
  }

  private static void requireTrustworthyIssuer(String value) {
    String message =
        "flow.auth.oidc.issuer must be an absolute https URL (http only for localhost): " + value;
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(message, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost();
    boolean valid =
        host != null
            && uri.getUserInfo() == null
            && uri.getFragment() == null
            && ("https".equals(scheme) || ("http".equals(scheme) && isLoopback(host)));
    if (!valid) {
      throw new IllegalStateException(message);
    }
  }

  // "localhost", any "*.localhost" name (resolves to loopback), 127.0.0.0/8 and ::1.
  private static boolean isLoopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost")
        || h.endsWith(".localhost")
        || h.matches("^127(\\.\\d{1,3}){3}$")
        || h.equals("[::1]");
  }
}
