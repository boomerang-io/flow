package io.boomerang.common.model;

import java.util.HashMap;
import java.util.Map;

/**
 * The labels the server sets, under a prefix reserved for it: a client never sets one, so a query
 * or an Insights grouping can trust them. The engine never reads them to decide anything.
 */
public abstract class ServerLabel {

  public static final String PREFIX = "boomerang.io/";

  /** On a child run a for-each run-workflow task started: the text item it ran for. */
  public static final String ITEM = PREFIX + "item";

  /** On an {@code ai} task run: the model its resolved {@code model} param asked for. */
  public static final String MODEL = PREFIX + "model";

  /** A copy of {@code labels} without the server's, for labels a client or a definition set. */
  public static Map<String, String> withoutServerLabels(Map<String, String> labels) {
    Map<String, String> clientLabels = new HashMap<>();
    if (labels != null) {
      labels.forEach(
          (key, value) -> {
            if (!key.startsWith(PREFIX)) {
              clientLabels.put(key, value);
            }
          });
    }
    return clientLabels;
  }

  private ServerLabel() {}
}
