package io.boomerang.core.model;

import io.boomerang.core.security.enums.AuthScope;
import lombok.Data;

import java.util.Date;

@Data
public class TokenCreateResponse {

  //  @JsonIgnore
  private String id;
  private AuthScope type;
  private String token;
  private Date expirationDate;
}
