package io.boomerang.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.security.enums.AuthScope;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('roles')}")
public class RoleEntity {

  // TODO if we make workflow by unique name this will need to move to be a prefixed string of
  // /team/team-name/workflow/workflowId or /user/userId etc
  private String id;
  private AuthScope type;
  private String name;
  private String description;
  private List<String> permissions = new LinkedList<>();
}
