import moment from "moment-timezone";

export default class DateHelper {
  // See tests for desired format.
  static getFormattedDateTime(date: Date = new Date()): string {
    return moment(date).format("M/D H:mm:ss");
  }

  static padLeadingZero(value: number): string | number {
    return value > 9 ? value : `0${value}`;
  }

  static convertUnixSecondsToDate(timestamp: any) {
    if (timestamp.length === 10) {
      timestamp = timestamp * 1000;
    }

    const date = new Date(timestamp.timestamp * 1000);
    const day = date.getUTCDate(); //returns day of month UTC
    const month = date.getUTCMonth();
    const year = date.getUTCFullYear();
    return `${year}-${month}-${day}`;
  }

  // slightly modified from http://stackoverflow.com/a/23259289 w/ renaming and es6 syntax. `ms`
  // is genuinely milliseconds despite the historical parameter name `seconds` - kept as-is so the
  // (zero) external callers and this method's own name do not have to change.
  static timeMillisecondsToTimeUnit(ms: number): string {
    const duration = moment.duration(ms);

    const hoursCount = Math.floor(duration.asHours());
    const singularHour = hoursCount === 1 ? `1 hr` : "";

    const minutesCount = Math.floor(duration.asMinutes()) % 60;
    const singularMinute = minutesCount === 1 ? `1 min` : "";

    const secondsCount = Math.floor(duration.asSeconds()) % 60;
    const singularSecond = secondsCount === 1 ? `1 sec` : "";

    const milliSecondsCount = duration.milliseconds();
    const singularMilisecond = milliSecondsCount === 1 ? `1 ms` : "";

    const hoursText = hoursCount > 1 ? `${hoursCount} hrs` : singularHour;
    const minutesText = minutesCount > 1 ? `${minutesCount} mins` : singularMinute;
    const secondsText = secondsCount > 1 ? `${secondsCount} secs` : singularSecond;
    const millisecondsText = milliSecondsCount > 1 ? `${milliSecondsCount} ms` : singularMilisecond;

    if (!hoursCount && !minutesCount && !secondsCount && millisecondsText) {
      return millisecondsText;
    }
    const message = `${hoursText} ${minutesText} ${secondsText}`;
    return message.trim();
  }

  // NOT trimmed - matches the pre-existing (untrimmed) behaviour so text with a leading/trailing
  // space stays identical for the 0/minutes-only cases; see dateHelper.spec.ts.
  static timeMinutesToTimeUnit(minutes: number): string {
    const duration = moment.duration(minutes, "minutes");

    const hoursCount = Math.floor(duration.asHours());
    const singularHour = hoursCount === 1 ? `1 hr` : "";

    const minutesCount = Math.floor(duration.asMinutes()) % 60;
    const singularMinute = minutesCount === 1 ? `1 min` : "";

    const hoursText = hoursCount > 1 ? `${hoursCount} hrs` : singularHour;
    const minutesText = minutesCount > 1 ? `${minutesCount} mins` : singularMinute;

    return `${hoursText} ${minutesText}`;
  }

  static timeAgo(datetimestamp: moment.MomentInput, duration: number): string {
    return moment(datetimestamp).add(duration, "milliseconds").fromNow();
  }

  static humanizedSimpleTimeAgo(datetimestamp: moment.MomentInput): string {
    const duration = moment.duration(moment().diff(moment(datetimestamp)));
    let time = 0;
    let timeName = "sec";

    if (duration.years() >= 1) {
      time = duration.years();
      timeName = "year";
    } else if (duration.months() >= 1) {
      time = duration.months();
      timeName = "month";
    } else if (duration.weeks() >= 1) {
      time = duration.weeks();
      timeName = "week";
    } else if (duration.days() >= 1) {
      time = duration.days();
      timeName = "day";
    } else if (duration.hours() >= 1) {
      time = duration.hours();
      timeName = "hour";
    } else if (duration.minutes() >= 1) {
      time = duration.minutes();
      timeName = "min";
    } else if (duration.seconds() >= 1) {
      time = duration.seconds();
    }

    return `${time} ${timeName}${time > 1 ? "s" : ""} ago`;
  }

  /**
   * Get human readdable difference from a time in the past to now
   * @param {String} datetimestamp
   * @returns {String}
   */
  static durationFromThenToNow(datetimestamp: moment.MomentInput): string {
    const diffMilli = moment().diff(moment(datetimestamp));
    return this.timeMillisecondsToTimeUnit(diffMilli);
  }

  static determineUpdatedMessage(minutesAgo: number): string {
    return minutesAgo === 0 ? "just now" : `${this.timeMinutesToTimeUnit(minutesAgo)} ago`;
  }
}

const exludedTimezones = ["GMT+0", "GMT-0", "ROC"];

export function transformTimeZone(timezone: string) {
  return { label: `${timezone} (UTC ${moment.tz(timezone).format("Z")})`, value: timezone };
}

export const timezoneOptions = moment.tz
  .names()
  .filter((tz) => !exludedTimezones.includes(tz))
  .map((element) => transformTimeZone(element));

export const defaultTimeZone = moment.tz.guess();

export const DATETIME_LOCAL_DISPLAY_FORMAT = "MMMM DD, YYYY h:mma";
export const DATETIME_LOCAL_INPUT_FORMAT = "YYYY-MM-DDTHH:mm";

// Formerly Utils/timeHelper.ts - collapsed in here per the C3 consolidation (see
// specifications/framework-review-wave.md).
export const getSimplifiedDuration = (seconds: number): string => {
  const duration = moment.duration(seconds, "seconds");
  const hour = 3600;
  const minute = 60;

  let result = `${Math.floor(seconds)}s`;

  if (seconds >= hour) {
    result = `${Math.floor(duration.asHours())}h`;
  } else if (seconds >= minute) {
    result = `${Math.floor(duration.asMinutes())}min`;
  }

  return result;
};

// Formerly Utils/timeSecondsToTimeUnit.ts - collapsed in here per the C3 consolidation (see
// specifications/framework-review-wave.md). Trailing space preserved when the minutes component
// is empty (existing quirk, pinned deliberately - see dateHelper.spec.ts).
export const timeSecondsToTimeUnit = (seconds: number): string => {
  if (!seconds) return "0 secs";

  const duration = moment.duration(seconds, "seconds");
  const hoursCount = Math.floor(duration.asHours());
  const minutesCount = Math.floor(duration.asMinutes()) % 60;
  const secondsCount = Math.floor(duration.asSeconds()) % 60;

  const hoursText = hoursCount > 1 ? `${hoursCount} hrs` : hoursCount === 1 ? `1 hr` : "";
  const minutesText = minutesCount > 1 ? `${minutesCount} mins` : minutesCount === 1 ? `1 min` : "";
  const secondsText = secondsCount > 1 ? `${secondsCount} secs` : secondsCount === 1 ? `1 sec` : "";

  if (hoursText) {
    return `${hoursText} ${minutesText}`;
  }

  if (minutesText) {
    return `${minutesText} ${secondsText}`;
  }

  return secondsText;
};
