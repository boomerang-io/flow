package io.boomerang.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

import io.boomerang.core.enums.UserType;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
  "id",
  "email",
  "name",
  "isFirstVisit",
  "type",
  "isShowHelp",
  "firstLoginDate",
  "lastLoginDate",
  "lowerLevelGroupIds",
  "pinnedToolIds",
  "favoritePackages",
  "personalizations",
  "notificationSettings",
  "status",
  "teams",
  "hasConsented"
})
public class ExternalUserProfile {

  private String id;
  private String email;
  private String name;
  private Boolean isFirstVisit;
  private UserType type;
  private Boolean isShowHelp;
  private String firstLoginDate;
  private String lastLoginDate;
  private List<Object> lowerLevelGroups = null;
  private List<Object> pinnedToolIds = null;
  private List<Object> favoritePackages = null;
  private String status;
  private List<Object> teams = null;
  private Boolean hasConsented;
}
