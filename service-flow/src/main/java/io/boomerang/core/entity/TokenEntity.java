package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.core.model.ResolvedPermissions;
import io.boomerang.security.enums.AuthScope;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('tokens')}")
public class TokenEntity {

  @Id private String id;
  private AuthScope type;
  private String name;
  private String description;
  private Date creationDate = new Date();
  private Date expirationDate;
  private String principal;
  private List<ResolvedPermissions> permissions = new LinkedList<>();
  private String token;
}
