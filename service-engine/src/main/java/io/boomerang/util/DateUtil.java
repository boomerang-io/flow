package io.boomerang.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

public final class DateUtil {

  private DateUtil() {
    // Do nothing
  }

  public static Date sanityNullDate(Date date) {
    if (date == null) {
      return null;
    }

    LocalDateTime localDateTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

    return asDate(localDateTime);
  }

  public static Date asDate(LocalDate localDate) {
    return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
  }

  public static Date asDate(LocalDateTime localDateTime) {
    return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
  }

  public static Date asDate(long timestamp) {
    LocalDateTime triggerTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), TimeZone.getDefault().toZoneId());

    return asDate(triggerTime);
  }

}
