package io.boomerang.core.audit;

import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.util.DataAdapterUtil;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The names of the secret values one audited write changed or cleared, collected on the request
 * thread while an {@link Audited} method runs and written to that event's {@code detail} by {@link
 * AuditAspect}. It holds names only: never a value, a hash of one, or its length. Outside an
 * audited call, comparing records nothing.
 */
public final class SecretChangeHolder {

  static final String CHANGED = "secretsChanged";
  static final String CLEARED = "secretsCleared";

  private static final ThreadLocal<Changes> CURRENT = new ThreadLocal<>();

  private record Changes(Set<String> changed, Set<String> cleared) {}

  private SecretChangeHolder() {}

  /** Start collecting for this thread; false when an outer audited call is already collecting. */
  static boolean open() {
    if (CURRENT.get() != null) {
      return false;
    }
    CURRENT.set(new Changes(new TreeSet<>(), new TreeSet<>()));
    return true;
  }

  /** Stop collecting and return the detail to record, or null when no secret changed. */
  static Map<String, Object> close() {
    Changes changes = CURRENT.get();
    CURRENT.remove();
    if (changes == null || (changes.changed().isEmpty() && changes.cleared().isEmpty())) {
      return null;
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    if (!changes.changed().isEmpty()) {
      detail.put(CHANGED, List.copyOf(changes.changed()));
    }
    if (!changes.cleared().isEmpty()) {
      detail.put(CLEARED, List.copyOf(changes.cleared()));
    }
    return detail;
  }

  /**
   * Record which of the named secrets a write changed or cleared: a name set after the write with a
   * different value than before is changed, and a name set before that is unset or gone after is
   * cleared. A value kept as it was records nothing.
   */
  public static void compare(Map<String, Object> before, Map<String, Object> after) {
    Changes changes = CURRENT.get();
    if (changes == null) {
      return;
    }
    after.forEach(
        (name, value) -> {
          if (isSet(value) && !Objects.equals(before.get(name), value)) {
            changes.changed().add(name);
          }
        });
    before.forEach(
        (name, value) -> {
          if (isSet(value) && !isSet(after.get(name))) {
            changes.cleared().add(name);
          }
        });
  }

  /** {@link #compare(Map, Map)} for one named secret. */
  public static void compare(String name, Object before, Object after) {
    Map<String, Object> was = new LinkedHashMap<>();
    was.put(name, normalised(before));
    Map<String, Object> is = new LinkedHashMap<>();
    is.put(name, normalised(after));
    compare(was, is);
  }

  /**
   * The secret values of a definition's params of {@code fieldType}, by {@code prefix} + name. A
   * param's value and default are one secret: it changed when either did.
   */
  public static Map<String, Object> secretValues(
      String prefix, List<AbstractParam> params, String fieldType) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (params != null) {
      params.stream()
          .filter(p -> p.getName() != null && Objects.equals(fieldType, p.getType()))
          .forEach(
              p ->
                  values.put(
                      prefix + p.getName(),
                      pair(normalised(p.getValue()), normalised(p.getDefaultValue()))));
    }
    return values;
  }

  /** The secret values of a node's or a schedule's params of {@code paramType}, by prefix + name. */
  public static Map<String, Object> secretRunValues(
      String prefix, List<RunParam> params, ParamType paramType) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (params != null) {
      params.stream()
          .filter(p -> p.getName() != null && paramType.equals(p.getType()))
          .forEach(p -> values.put(prefix + p.getName(), normalised(p.getValue())));
    }
    return values;
  }

  // A value and a default, unset when both are; compared as a pair.
  private static Object pair(Object value, Object defaultValue) {
    return (value == null && defaultValue == null) ? null : Arrays.asList(value, defaultValue);
  }

  // No value, a blank, and the redaction marker an earlier release may have stored are all unset.
  private static Object normalised(Object value) {
    if (value == null
        || (value instanceof String text && text.isBlank())
        || DataAdapterUtil.REDACTED.equals(value)) {
      return null;
    }
    return value;
  }

  private static boolean isSet(Object value) {
    return value != null;
  }
}
