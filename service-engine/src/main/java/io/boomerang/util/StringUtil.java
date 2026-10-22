package io.boomerang.util;

public class StringUtil {
  
  /**
   * This method converts a string to kebabCase similar to lodash
   * 
   * Kebab case is all in lower case with words separated by a hyphen '-'
   * @param value The string to convert to kebab case
   * @return  The string in kebab case
   */
  public static String kebabCase(String value) {
    value = value.replaceAll("[^A-Za-z0-9' \\-]", "");
    value = value.replaceAll("\\s+", "-");
    value = value.replaceAll("'", "-");
    value = value.replaceAll("\\-+", "-");
    return value.toLowerCase();
  }
}
