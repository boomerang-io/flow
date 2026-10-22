package io.boomerang.core.model;

import io.boomerang.security.enums.AuthScope;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class TokenCreateRequest {

  private AuthScope type;
  private String name;
  private String principal;
  private String description;
  private Date expirationDate;
  private List<String> permissions;
  private String role;
}
