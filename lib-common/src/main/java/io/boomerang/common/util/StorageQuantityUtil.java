package io.boomerang.common.util;

public class StorageQuantityUtil {

  /**
   * Parses a Kubernetes-style storage quantity ("1Gi", "500Mi", "2Gi") into whole Gi, for
   * comparing against a quota. A bare number ("2") is legacy shorthand for Gi. The input string
   * itself is never mutated - callers keep the original value to send on to Kubernetes.
   *
   * @param size the quantity to parse
   * @return the quantity in Gi
   */
  public static double toGi(String size) {
    String trimmed = size.trim();
    if (trimmed.endsWith("Gi")) {
      return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2));
    } else if (trimmed.endsWith("Mi")) {
      return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2)) / 1024;
    } else if (trimmed.endsWith("G")) {
      return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1_000_000_000d
          / (1024d * 1024 * 1024);
    } else if (trimmed.endsWith("M")) {
      return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1_000_000d
          / (1024d * 1024 * 1024);
    }
    // Legacy shorthand - a bare number was always interpreted as Gi.
    return Double.parseDouble(trimmed);
  }
}
