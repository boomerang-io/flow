package io.boomerang.core.model;

import lombok.Data;

@Data
public class UserSettings {

  private Boolean isFirstVisit = true;
  private Boolean isShowHelp = true;
  private Boolean hasConsented = false;
}
