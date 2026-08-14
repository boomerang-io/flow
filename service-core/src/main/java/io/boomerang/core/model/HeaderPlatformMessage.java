package io.boomerang.core.model;

import lombok.Data;

@Data
public class HeaderPlatformMessage {

  private String kind;
  private String title;
  private String message;
}
