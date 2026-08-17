package io.boomerang.core.model;

import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;

@Data
public class Token {

  //  @JsonIgnore
  private String id;
  private AuthScope type;
  private String name;
  private String description;
  private Date creationDate = new Date();
  private Date expirationDate;
  private boolean valid;
  private String principal;
  private List<ResolvedPermissions> permissions = new LinkedList<>();
  // T6-1: surfaced for audit/UI visibility - null on every human/pre-existing token.
  private TokenActorKind actorKind;
  private String createdBy;
  private Date lastUsedAt;

  public Token() {}

  public Token(AuthScope type) {
    super();
    this.setType(type);
  }

  public Token(TokenEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
