package io.boomerang.util;

import java.util.List;
import java.util.Optional;
import io.boomerang.common.model.AbstractParam;

public class DataAdapterUtil {
  public enum FieldType {
    PASSWORD("password");

    private final String value;

    private FieldType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /**
   * Method for filtering sensitive data (e.g. make null the value of any password type field)
   *
   * @param properties
   * @param isDefaultValue - Specify if the defaultValue or the value should be made null
   * @param fieldType
   * @return
   */
  public static List<? extends AbstractParam> filterValueByFieldType(
      List<? extends AbstractParam> properties, boolean isDefaultValue, String fieldType) {
    if (properties == null || fieldType == null) return null;
    Optional<? extends AbstractParam> passProp =
        properties.stream()
            .filter(
                f ->
                    fieldType.equals(f.getType())
                        && (isDefaultValue ? f.getDefaultValue() != null : f.getValue() != null))
            .findAny();
    if (passProp.isPresent()) {
      if (isDefaultValue) {
        passProp.get().setDefaultValue(null);
      } else {
        passProp.get().setValue(null);
      }
      passProp.get().setHiddenValue(Boolean.TRUE);
    }
    return properties;
  }
}
