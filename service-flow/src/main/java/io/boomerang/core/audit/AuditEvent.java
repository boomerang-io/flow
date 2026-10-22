package io.boomerang.core.audit;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.core.model.Token;
import lombok.Data;

@Data
@JsonInclude(Include.NON_NULL)
public class AuditEvent {

  private AuditType type;
  private Date date = new Date();
  private AuditActor actor;

  public AuditEvent() {
    // TODO Auto-generated constructor stub
  }

  public AuditEvent(AuditType type, Token token) {
    this.type = type;
    this.actor = new AuditActor(token);
  }
}
