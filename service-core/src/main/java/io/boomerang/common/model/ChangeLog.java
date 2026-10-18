package io.boomerang.common.model;

import java.util.Date;
import lombok.Data;

@Data
public class ChangeLog {
  private String author;
  private String reason;
  private Date date;

  public ChangeLog() {}

  public ChangeLog(String reason) {
    super();
    this.reason = reason;
    this.date = new Date();
  }

  public ChangeLog(String author, String reason) {
    super();
    this.author = author;
    this.reason = reason;
    this.date = new Date();
  }

  @Override
  public String toString() {
    return "ChangeLog [author=" + author + ", reason=" + reason + ", date=" + date + "]";
  }
}
