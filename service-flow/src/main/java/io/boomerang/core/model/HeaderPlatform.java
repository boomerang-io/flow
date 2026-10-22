package io.boomerang.core.model;

import lombok.Data;

@Data
public class HeaderPlatform {

  private String version;
  private String name;
  private String signOutUrl;
  private String communityUrl;
  private String appName;
  private String platformName;
  private Boolean displayLogo;
  private String logoURL;
  private Boolean privateTeams;
  private Boolean sendMail;
  private String baseServicesUrl;
  private String baseEnvUrl;
}
