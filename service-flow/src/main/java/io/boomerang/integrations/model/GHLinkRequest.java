package io.boomerang.integrations.model;

import lombok.Data;

@Data
public class GHLinkRequest {

  private String ref;
  private String team;
}
