import moment from "moment-timezone";

export default class DateHelper {
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
    return formatDuration(moment().diff(moment(datetimestamp)));
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

// Date and time formats used in more than one place, named by what they show.
// The value a date range filter's date picker holds.
export const DATE_PICKER_FORMAT = "MM/DD/YYYY";
// A timestamp in a table or a detail row.
export const DATETIME_DISPLAY_FORMAT = "YYYY-MM-DD hh:mm A";
// A date in a table or a detail row.
export const DATE_DISPLAY_FORMAT = "YYYY-MM-DD";
// A token's expiry or a quota's reset date.
export const DATE_LONG_DISPLAY_FORMAT = "MMMM DD, YYYY";
// A date in running text.
export const DATE_SHORT_DISPLAY_FORMAT = "MMM D, YYYY";
// A one-off schedule's moment, or a last sign-in.
export const DATETIME_READABLE_FORMAT = "D MMM YYYY, h:mm A";
// A schedule's next run.
export const NEXT_RUN_FORMAT = "ddd D MMM, h:mm A";
// A time of day.
export const TIME_DISPLAY_FORMAT = "h:mm A";

/**
 * The one way a run or task duration is shown, from milliseconds: "1 min 48 s", "22 min",
 * "1 h 10 min"; sub-second durations read "< 1 s".
 */
export function formatDuration(ms: number): string {
  if (!ms || ms <= 0) {
    return "0 s";
  }
  if (ms < 1000) {
    return "< 1 s";
  }
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const restSeconds = seconds % 60;
  if (minutes < 60) {
    return restSeconds ? `${minutes} min ${restSeconds} s` : `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes ? `${hours} h ${restMinutes} min` : `${hours} h`;
}
