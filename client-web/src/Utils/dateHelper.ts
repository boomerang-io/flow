import moment from "moment-timezone";

export default class DateHelper {
  // Slightly modified from http://stackoverflow.com/a/23259289: milliseconds to "1 hr 2 mins 3 secs".
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

// Formerly Utils/timeHelper.ts - collapsed in here.
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
