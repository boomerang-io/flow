package io.boomerang.integrations.model;

import lombok.Data;

@Data
public class GHShareRequest {

  private String workspace;
  private boolean shared;
}
