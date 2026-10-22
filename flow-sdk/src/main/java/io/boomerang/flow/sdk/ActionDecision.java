package io.boomerang.flow.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One decision in {@link Actions#decide}: the action, approved or rejected, with the decider's
 * comments. On the wire this is the server's {@code ActionRequest}; the decider is the token's
 * principal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionDecision(String id, boolean approved, String comments) {

  public static ActionDecision approve(String id, String comments) {
    return new ActionDecision(id, true, comments);
  }

  public static ActionDecision reject(String id, String comments) {
    return new ActionDecision(id, false, comments);
  }
}
