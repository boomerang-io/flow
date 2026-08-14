package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.UserSettings;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('users')}")
public class UserEntity {

  @Id private String id;
  @Indexed
  private String email;
  private String name;
  private String displayName;
  private UserType type = UserType.user;
  private Date creationDate = new Date();
  private Date lastLoginDate;
  private UserStatus status = UserStatus.active;
  private Map<String, String> labels = new HashMap<>();
  private UserSettings settings = new UserSettings();
}
