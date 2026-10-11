import cronstrue from "cronstrue";
import moment from "moment-timezone";
import { DayOfWeekKey, DayOfWeekCronAbbreviation, ScheduleUnion } from "Types";

export const daysOfWeekCronList: Array<{ labelText: string, value: DayOfWeekKey, id: DayOfWeekKey, cron: DayOfWeekCronAbbreviation, cronNumber: [index: string, secondIndex?: string], key: DayOfWeekKey}> = [
  { labelText: "Sunday", value: "sunday", id: "sunday", cron: "SUN", cronNumber: ["0", "7"], key: "sunday" },
  { labelText: "Monday", value: "monday", id: "monday", cron: "MON", cronNumber: ["1"], key: "monday" },
  { labelText: "Tuesday", value: "tuesday", id: "tuesday", cron: "TUE", cronNumber: ["2"], key: "tuesday" },
  { labelText: "Wednesday", value: "wednesday", id: "wednesday", cron: "WED", cronNumber: ["3"], key: "wednesday" },
  { labelText: "Thursday", value: "thursday", id: "thursday", cron: "THU", cronNumber: ["4"], key: "thursday" },
  { labelText: "Friday", value: "friday", id: "friday", cron: "FRI", cronNumber: ["5"], key: "friday" },
  { labelText: "Saturday", value: "saturday", id: "saturday", cron: "SAT", cronNumber: ["6"], key: "saturday" },
];

export const cronDayNumberMap: Record<DayOfWeekKey, DayOfWeekCronAbbreviation> = {
  sunday: "SUN",
  monday: "MON",
  tuesday: "TUE",
  wednesday: "WED",
  thursday: "THU",
  friday: "FRI",
  saturday: "SAT",
};

/** The choices a schedule's "When" offers. Each maps onto a stored ScheduleType - see frequencyToSchedule. */
export type ScheduleFrequency = "once" | "hourly" | "daily" | "weekdays" | "weekly" | "custom";

export const ALL_DAYS: Array<DayOfWeekKey> = daysOfWeekCronList.map((day) => day.value);
export const WEEKDAYS: Array<DayOfWeekKey> = ["monday", "tuesday", "wednesday", "thursday", "friday"];
// Monday first, the order the day buttons show.
export const DAYS_MONDAY_FIRST: Array<DayOfWeekKey> = [...ALL_DAYS.slice(1), "sunday"];

const HOURLY_CRON = /^([0-5]?\d) \* \* \* \*$/;

function dayOfToken(token: string): DayOfWeekKey | undefined {
  const upper = token.toUpperCase();
  return daysOfWeekCronList.find((day) => day.cron === upper || day.cronNumber.includes(upper))?.value;
}

function parseDays(field: string): Array<DayOfWeekKey> | undefined {
  if (field === "*" || field === "?") {
    return [...ALL_DAYS];
  }
  const days = new Set<DayOfWeekKey>();
  for (const token of field.split(",")) {
    const [from, to] = token.split("-");
    const start = dayOfToken(from);
    const end = to === undefined ? start : dayOfToken(to);
    if (!start || !end) {
      return undefined;
    }
    const startIndex = ALL_DAYS.indexOf(start);
    // A range ending on Sunday may be written 7 (MON-7, 1-7), which maps to index 0.
    const endIndex = end === "sunday" && to !== undefined && startIndex > 0 ? 7 : ALL_DAYS.indexOf(end);
    for (let index = startIndex; index <= endIndex; index++) {
      days.add(ALL_DAYS[index % 7]);
    }
  }
  return ALL_DAYS.filter((day) => days.has(day));
}

/**
 * The cron for a set of days at a time ("HH:mm", 24-hour), in the five-field UNIX form the scheduler
 * parses: minute, hour, day of month, month, day of week.
 */
export function weeklyCron(days: Array<DayOfWeekKey>, time: string): string {
  const [hour = "0", minute = "0"] = (time || "00:00").split(":");
  const selected = ALL_DAYS.filter((day) => days.includes(day));
  const dayField = selected.length === 0 || selected.length === 7 ? "*" : selected.map((day) => cronDayNumberMap[day]).join(",");
  return `${Number(minute)} ${Number(hour)} * * ${dayField}`;
}

/**
 * Read the days and time back out of a day-and-time cron in the UNIX form weeklyCron writes. With `legacy`,
 * also the two orders earlier versions of this form saved for "cron" schedules ("0 mm HH * DAYS" and
 * Quartz's "0 mm HH ? * DAYS") - only then, since "0 9 1 * *" is also a valid monthly UNIX cron.
 * Undefined for any other expression.
 */
export function parseWeeklyCron(cron?: string, legacy = false): { days: Array<DayOfWeekKey>; time: string } | undefined {
  const fields = (cron ?? "").trim().split(/\s+/);
  let minute: string, hour: string, dayField: string;
  if (fields.length === 5 && fields[2] === "*" && fields[3] === "*") {
    [minute, hour, , , dayField] = fields;
  } else if (legacy && fields.length === 5 && fields[0] === "0" && fields[3] === "*") {
    [, minute, hour, , dayField] = fields;
  } else if (legacy && fields.length === 6 && fields[0] === "0" && fields[4] === "*") {
    [, minute, hour, , , dayField] = fields;
  } else {
    return undefined;
  }
  if (!/^\d{1,2}$/.test(minute) || !/^\d{1,2}$/.test(hour) || Number(minute) > 59 || Number(hour) > 23) {
    return undefined;
  }
  const days = parseDays(dayField);
  if (!days) {
    return undefined;
  }
  return { days, time: `${hour.padStart(2, "0")}:${minute.padStart(2, "0")}` };
}

const sameDays = (a: Array<DayOfWeekKey>, b: Array<DayOfWeekKey>) => a.length === b.length && a.every((day) => b.includes(day));

/** The "When" choice and its settings for a stored schedule. */
export function scheduleFrequency(schedule: Pick<ScheduleUnion, "type"> & { cronSchedule?: string }): {
  frequency: ScheduleFrequency;
  days: Array<DayOfWeekKey>;
  time: string;
  minute: string;
} {
  const blank = { days: [...WEEKDAYS], time: "09:00", minute: "0" };
  if (schedule.type === "runOnce") {
    return { frequency: "once", ...blank };
  }
  const hourly = HOURLY_CRON.exec(schedule.cronSchedule?.trim() ?? "");
  if (schedule.type === "advancedCron") {
    return hourly ? { frequency: "hourly", ...blank, minute: String(Number(hourly[1])) } : { frequency: "custom", ...blank };
  }
  const weekly = parseWeeklyCron(schedule.cronSchedule, true);
  if (!weekly) {
    return { frequency: "custom", ...blank };
  }
  const frequency = sameDays(weekly.days, ALL_DAYS) ? "daily" : sameDays(weekly.days, WEEKDAYS) ? "weekdays" : "weekly";
  return { frequency, ...blank, ...weekly };
}

/** The stored type and cron for a "When" choice. Once keeps its date; custom keeps the typed expression. */
export function frequencyToSchedule(
  frequency: ScheduleFrequency,
  { days, time, minute, cronSchedule }: { days: Array<DayOfWeekKey>; time: string; minute: string; cronSchedule?: string },
): { type: ScheduleUnion["type"]; cronSchedule?: string; days: Array<DayOfWeekKey> } {
  switch (frequency) {
    case "once":
      return { type: "runOnce", days: [] };
    case "hourly":
      return { type: "advancedCron", cronSchedule: `${Number(minute) || 0} * * * *`, days: [] };
    case "daily":
      return { type: "cron", cronSchedule: weeklyCron(ALL_DAYS, time), days: [...ALL_DAYS] };
    case "weekdays":
      return { type: "cron", cronSchedule: weeklyCron(WEEKDAYS, time), days: [...WEEKDAYS] };
    case "weekly":
      return { type: "cron", cronSchedule: weeklyCron(days, time), days };
    default:
      return { type: "advancedCron", cronSchedule, days: [] };
  }
}

const formatTime = (time: string) => moment(time, "HH:mm").format("h:mm A");

function describeDays(days: Array<DayOfWeekKey>): string {
  if (sameDays(days, ALL_DAYS)) {
    return "every day";
  }
  if (sameDays(days, WEEKDAYS)) {
    return "Monday to Friday";
  }
  const names = DAYS_MONDAY_FIRST.filter((day) => days.includes(day)).map((day) => day[0].toUpperCase() + day.slice(1));
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : `every ${names[0] ?? ""}`;
}

/**
 * "Every day at 9:00 AM", "Monday to Friday at 9:00 AM", "Every hour at :15", or the cron read by cronstrue.
 * `legacy` reads the older orders a "cron" schedule may be stored in (see parseWeeklyCron).
 */
export function describeCron(cron?: string, legacy = false): string {
  if (!cron) {
    return "---";
  }
  const hourly = HOURLY_CRON.exec(cron.trim());
  if (hourly) {
    return `Every hour at :${hourly[1].padStart(2, "0")}`;
  }
  const weekly = parseWeeklyCron(cron, legacy);
  if (weekly) {
    const days = describeDays(weekly.days);
    return `${days[0].toUpperCase()}${days.slice(1)} at ${formatTime(weekly.time)}`;
  }
  try {
    return cronstrue.toString(cron);
  } catch (e) {
    return cron;
  }
}

/** The next time a day-and-time or hourly choice fires after now, in the schedule's time zone. */
export function nextOccurrence(
  frequency: ScheduleFrequency,
  { days, time, minute, timezone }: { days: Array<DayOfWeekKey>; time: string; minute: string; timezone: string },
): moment.Moment | undefined {
  const now = moment.tz(timezone);
  if (frequency === "hourly") {
    const next = now.clone().minute(Number(minute) || 0).second(0).millisecond(0);
    return next.isAfter(now) ? next : next.add(1, "hour");
  }
  const chosen = frequency === "daily" ? ALL_DAYS : frequency === "weekdays" ? WEEKDAYS : frequency === "weekly" ? days : [];
  if (!chosen.length || !/^\d{1,2}:\d{2}$/.test(time)) {
    return undefined;
  }
  const [hour, min] = time.split(":").map(Number);
  for (let offset = 0; offset <= 7; offset++) {
    const candidate = now.clone().add(offset, "day").hour(hour).minute(min).second(0).millisecond(0);
    if (candidate.isAfter(now) && chosen.includes(ALL_DAYS[candidate.day()])) {
      return candidate;
    }
  }
  return undefined;
}
