package io.boomerang.audit;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_NULL)
public class AuditEvent {

  private AuditType type;
  private Date date = new Date();
  
  public AuditEvent() {
    // TODO Auto-generated constructor stub
  }
  
  public AuditEvent(AuditType type) {
    this.type = type;
  }

  public AuditType getType() {
    return type;
  }
  public void setType(AuditType type) {
    this.type = type;
  }
  public Date getDate() {
    return date;
  }
  public void setDate(Date date) {
    this.date = date;
  }
}
