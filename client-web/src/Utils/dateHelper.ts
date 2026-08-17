//@ts-nocheck
import moment from "moment-timezone";

export default class DateHelper {
  // See tests for desired format.
  static getFormattedDateTime(date = new Date()) {
    return `${date.getMonth() + 1}/${date.getDate()} ${date.getHours()}:${this.padLeadingZero(
      date.getMinutes()
    )}:${this.padLeadingZero(date.getSeconds())}`;
  }

  static padLeadingZero(value) {
    return value > 9 ? value : `0${value}`;
  }

  static convertUnixSecondsToDate(timestamp) {
    if (timestamp.length === 10) {
      timestamp = timestamp * 1000;
    }

    const date = new Date(timestamp.timestamp * 1000);
    const day = date.getUTCDate(); //returns day of month UTC
    const month = date.getUTCMonth();
    const year = date.getUTCFullYear();
    return `${year}-${month}-${day}`;
  }

  //slightly modified from http://stackoverflow.com/a/23259289 w/ renaming and es6 syntax

  static timeMillisecondsToTimeUnit(seconds) {
    const hoursCount = Math.floor(seconds / 3600000);
    const singularHour = hoursCount === 1 ? `1 hr` : "";

    const minutesCount = Math.floor((seconds % 3600000) / 60000);
    const singularMinute = minutesCount === 1 ? `1 min` : "";

    const secondsCount = Math.floor(((seconds % 3600000) % 60000) / 1000);
    const singularSecond = secondsCount === 1 ? `1 sec` : "";

    const milliSecondsCount = Math.floor(seconds % 1000);
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

  static timeMinutesToTimeUnit(minutes) {
    const hoursCount = Math.floor(minutes / 60);
    const singularHour = hoursCount === 1 ? `1 hr` : "";

    const minutesCount = Math.floor(minutes % 60);
    const singularMinute = minutesCount === 1 ? `1 min` : "";

    const hoursText = hoursCount > 1 ? `${hoursCount} hrs` : singularHour;
    const minutesText = minutesCount > 1 ? `${minutesCount} mins` : singularMinute;

    return `${hoursText} ${minutesText}`;
  }

  static timeAgo(datetimestamp, duration) {
    return moment(datetimestamp).add(duration, "milliseconds").fromNow();
  }

  static humanizedSimpleTimeAgo(datetimestamp) {
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
  static durationFromThenToNow(datetimestamp) {
    const diffMilli = Date.now() - Date.parse(datetimestamp);
    return this.timeMillisecondsToTimeUnit(diffMilli);
  }

  static determineUpdatedMessage(minutesAgo) {
    return minutesAgo === 0 ? "just now" : `${this.timeMinutesToTimeUnit(minutesAgo)} ago`;
  }
}

const exludedTimezones = ["GMT+0", "GMT-0", "ROC"];

export function transformTimeZone(timezone) {
  return { label: `${timezone} (UTC ${moment.tz(timezone).format("Z")})`, value: timezone };
}

export const timezoneOptions = moment.tz
  .names()
  .filter((tz) => !exludedTimezones.includes(tz))
  .map((element) => transformTimeZone(element));

export const defaultTimeZone = moment.tz.guess();

export const DATETIME_LOCAL_DISPLAY_FORMAT = "MMMM DD, YYYY h:mma";
export const DATETIME_LOCAL_INPUT_FORMAT = "YYYY-MM-DDTHH:mm";
