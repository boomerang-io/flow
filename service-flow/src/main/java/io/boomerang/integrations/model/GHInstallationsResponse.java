package io.boomerang.integrations.model;

import lombok.Data;

import java.util.List;

@Data
public class GHInstallationsResponse {

  private Integer appId;
  private Integer installationId;
  private String orgSlug;
  private String orgUrl;
  private Integer orgId;
  private String orgType;
  private List<String> events;
  private List<String> repositories;
}
