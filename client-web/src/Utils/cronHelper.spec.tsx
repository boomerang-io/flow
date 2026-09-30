import { describeCron, frequencyToSchedule, parseWeeklyCron, scheduleFrequency, weeklyCron } from "./cronHelper";

describe("weeklyCron", () => {
  it("writes minute, hour, day of month, month, day of week - the UNIX order the scheduler parses", () => {
    expect(weeklyCron(["monday", "wednesday", "friday"], "09:30")).toBe("30 9 * * MON,WED,FRI");
    expect(weeklyCron(["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"], "18:00")).toBe("0 18 * * *");
  });
});

describe("parseWeeklyCron", () => {
  it("reads the UNIX form back", () => {
    expect(parseWeeklyCron("30 9 * * MON,WED,FRI")).toEqual({ days: ["monday", "wednesday", "friday"], time: "09:30" });
  });

  it("reads the two orders earlier versions of the form saved, when asked to", () => {
    expect(parseWeeklyCron("0 30 09 * MON,TUE")).toBeUndefined();
    expect(parseWeeklyCron("0 30 09 * MON,TUE", true)).toEqual({ days: ["monday", "tuesday"], time: "09:30" });
    expect(parseWeeklyCron("0 30 09 ? * MON-FRI", true)).toEqual({
      days: ["monday", "tuesday", "wednesday", "thursday", "friday"],
      time: "09:30",
    });
  });

  it("expands ranges, including one ending on Sunday written as 7", () => {
    expect(parseWeeklyCron("0 6 * * 5-7")?.days).toEqual(["sunday", "friday", "saturday"]);
  });

  it("returns nothing for an expression that isn't days and a time", () => {
    expect(parseWeeklyCron("*/5 * * * *")).toBeUndefined();
    expect(parseWeeklyCron("0 9 1 * *")).toBeUndefined();
  });
});

describe("scheduleFrequency", () => {
  it("opens a stored schedule on the choice that made it", () => {
    expect(scheduleFrequency({ type: "runOnce" }).frequency).toBe("once");
    expect(scheduleFrequency({ type: "advancedCron", cronSchedule: "15 * * * *" })).toMatchObject({ frequency: "hourly", minute: "15" });
    expect(scheduleFrequency({ type: "advancedCron", cronSchedule: "0 2 * * 0" }).frequency).toBe("custom");
    expect(scheduleFrequency({ type: "cron", cronSchedule: "0 9 * * *" }).frequency).toBe("daily");
    expect(scheduleFrequency({ type: "cron", cronSchedule: "0 9 * * MON,TUE,WED,THU,FRI" }).frequency).toBe("weekdays");
    expect(scheduleFrequency({ type: "cron", cronSchedule: "0 30 09 * MON,TUE" })).toMatchObject({ frequency: "weekly", time: "09:30" });
    expect(scheduleFrequency({ type: "cron", cronSchedule: "0 9 * * MON,WED" })).toMatchObject({
      frequency: "weekly",
      days: ["monday", "wednesday"],
      time: "09:00",
    });
  });
});

describe("frequencyToSchedule", () => {
  const settings = { days: ["tuesday" as const], time: "07:45", minute: "20", cronSchedule: "0 2 * * 0" };

  it("stores each choice as one of the three schedule types", () => {
    expect(frequencyToSchedule("once", settings).type).toBe("runOnce");
    expect(frequencyToSchedule("hourly", settings)).toMatchObject({ type: "advancedCron", cronSchedule: "20 * * * *" });
    expect(frequencyToSchedule("daily", settings)).toMatchObject({ type: "cron", cronSchedule: "45 7 * * *" });
    expect(frequencyToSchedule("weekdays", settings)).toMatchObject({ type: "cron", cronSchedule: "45 7 * * MON,TUE,WED,THU,FRI" });
    expect(frequencyToSchedule("weekly", settings)).toMatchObject({ type: "cron", cronSchedule: "45 7 * * TUE" });
    expect(frequencyToSchedule("custom", settings)).toMatchObject({ type: "advancedCron", cronSchedule: "0 2 * * 0" });
  });
});

describe("describeCron", () => {
  it("says what a schedule does in words", () => {
    expect(describeCron("0 9 * * *")).toBe("Every day at 9:00 AM");
    expect(describeCron("0 9 * * MON,TUE,WED,THU,FRI")).toBe("Monday to Friday at 9:00 AM");
    expect(describeCron("30 17 * * MON,WED,FRI")).toBe("Monday, Wednesday and Friday at 5:30 PM");
    expect(describeCron("5 * * * *")).toBe("Every hour at :05");
    expect(describeCron("0 0 1 * *")).toBe("At 12:00 AM, on day 1 of the month");
  });
});
