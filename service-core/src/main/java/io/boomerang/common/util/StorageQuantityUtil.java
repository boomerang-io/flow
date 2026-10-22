package io.boomerang.common.util;

import java.util.regex.Pattern;

public class StorageQuantityUtil {

  private static final Pattern BARE_NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");

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

  /**
   * Writes a size the way Kubernetes must read it. A bare number is the Gi shorthand authors and
   * the v3 data use, so "2" becomes "2Gi"; a Kubernetes Quantity would otherwise read it as two
   * bytes. Anything else (a unit already present) is returned trimmed and unchanged.
   */
  public static String normalise(String size) {
    if (size == null) {
      return null;
    }
    String trimmed = size.trim();
    if (BARE_NUMBER.matcher(trimmed).matches()) {
      return trimmed.endsWith(".0") ? trimmed.substring(0, trimmed.length() - 2) + "Gi" : trimmed + "Gi";
    }
    return trimmed;
  }
}
