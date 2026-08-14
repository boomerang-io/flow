package io.boomerang.core.model;

import java.util.List;
import lombok.Data;

@Data
public class HeaderNavigationResponse {
  private HeaderPlatform platform;
  private HeaderFeatures features;
  private List<HeaderNavigation> navigation;
  private List<HeaderOption> featuredServices;
  private HeaderPlatformMessage platformMessage;
}
