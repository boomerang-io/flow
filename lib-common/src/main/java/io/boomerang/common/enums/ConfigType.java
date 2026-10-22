package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.awt.*;
import java.util.Arrays;

/** Type for the UI elements (canvas). Must map to a ParamType */
public enum ConfigType {
  TEXT("text"),
  TEXTAREA("textarea"),
  EMAIL("email"),
  NUMBER("number"),
  URL("url"),
  BOOLEAN("boolean"),
  PASSWORD("password"),
  SELECT("select"),
  MULTISELECT("multiselect"),
  JSON("json"),
  TEXTEDITOR("texteditor"),
  TEXTEDITORJS("texteditor::javascript"),
  TEXTEDITORTEXT("texteditor::text"),
  TEXTEDITORSHELL("texteditor::shell"),
  TEXTEDITORYAML("texteditor::yaml");

  private String label;

  ConfigType(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  public static ConfigType getConfigType(String label) {
    return Arrays.asList(ConfigType.values()).stream()
        .filter(value -> value.getLabel().equals(label))
        .findFirst()
        .orElse(null);
  }
}
