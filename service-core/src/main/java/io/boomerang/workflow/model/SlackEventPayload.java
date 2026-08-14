package io.boomerang.workflow.model;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * This model services dual purpose as both the Challenge Payload and the Events
 * Payload.
 * 
 * @see <a href="https://api.slack.com/events-api#subscriptions">Subscribing to
 *      event types</a>
 * @see <a href="https://api.slack.com/events-api#receiving_events">Receiving
 *      Events</a>
 */
public class SlackEventPayload {

  private String token;

  private String challenge;

  private String type;

  private Map<String, Object> details = new LinkedHashMap<>();

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getChallenge() {
    return challenge;
  }

  public void setChallenge(String challenge) {
    this.challenge = challenge;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  @JsonAnySetter
  public void setDetail(String key, Object value) {
    details.put(key, value);
  }
}
