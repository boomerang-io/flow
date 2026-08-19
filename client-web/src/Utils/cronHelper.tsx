
import { DayOfWeekKey, DayOfWeekCronAbbreviation } from "Types";

export const daysOfWeekCronList: Array<{ labelText: string, value: DayOfWeekKey, id: DayOfWeekKey, cron: DayOfWeekCronAbbreviation, cronNumber: [index: string, secondIndex?: string], key: DayOfWeekKey}> = [
  { labelText: "Sunday", value: "sunday", id: "sunday", cron: "SUN", cronNumber: ["0", "7"], key: "sunday" },
  { labelText: "Monday", value: "monday", id: "monday", cron: "MON", cronNumber: ["1"], key: "monday" },
  { labelText: "Tuesday", value: "tuesday", id: "tuesday", cron: "TUE", cronNumber: ["2"], key: "tuesday" },
  { labelText: "Wednesday", value: "wednesday", id: "wednesday", cron: "WED", cronNumber: ["3"], key: "wednesday" },
  { labelText: "Thursday", value: "thursday", id: "thursday", cron: "THU", cronNumber: ["4"], key: "thursday" },
  { labelText: "Friday", value: "friday", id: "friday", cron: "FRI", cronNumber: ["5"], key: "friday" },
  { labelText: "Saturday", value: "saturday", id: "saturday", cron: "SAT", cronNumber: ["6"], key: "saturday" },
];

const cronToDayMap: Record<DayOfWeekCronAbbreviation, number> = {
  SUN: 0,
  MON: 1,
  TUE: 2,
  WED: 3,
  THU: 4,
  FRI: 5,
  SAT: 6,
};

export const cronDayNumberMap: Record<DayOfWeekKey, DayOfWeekCronAbbreviation> = {
  sunday: "SUN",
  monday: "MON",
  tuesday: "TUE",
  wednesday: "WED",
  thursday: "THU",
  friday: "FRI",
  saturday: "SAT",
};

function createListOfDays(lowEnd: number, highEnd: number) {
  let output = "";
  for (let i = lowEnd; i <= highEnd; i++) {
    output += `${i},`;
  }
  if (output[output.length - 1] === ",") {
    return output.slice(0, -1);
  } else {
    return output;
  }
}

//@ts-ignore
export function cronToDateTime(
  hasSchedule: boolean,
  cronExp: string
): { selectedDays: { [day in DayOfWeekKey]: boolean }; cronTime: string } {
  if (!cronExp) {
    //@ts-ignore
    return { selectedDays: {}, cronTime: "" };
  }

  const cronToData = hasSchedule ? cronExp.split(" ") : undefined;
  let daysSlot = cronToData?.length === 5 ? 4 : 5;
  let cronDays = cronToData
    ? cronToData[daysSlot] === "*" || !cronToData[daysSlot]
      ? []
      : cronToData[daysSlot].split(",")
    : [];
  let selectedDays = {};
  //@ts-ignore
  let cronDaysAdjusted = [];

  cronDays.forEach((entry) => {
    let output = entry;
    if (entry.includes("-")) {
      let parts = entry.split("-");
      let lowEnd = parts[0];
      let highEnd = parts[0];
      if (lowEnd.length > 1) {
        //convert to number
        //@ts-ignore
        lowEnd = cronToDayMap[parts[0]];
      }
      if (highEnd.length > 1) {
        //convert to number
        //@ts-ignore
        highEnd = cronToDayMap[parts[1]];
      }
      //@ts-ignore
      output = createListOfDays(lowEnd, highEnd);
    }
    cronDaysAdjusted.push(output);
  });

  daysOfWeekCronList.forEach(
    (day) =>
      //@ts-ignore
      (selectedDays[day.value] =
        //@ts-ignore
        !!cronDaysAdjusted.find((cron) => cron === day.cron || day.cronNumber.find((num) => cron.includes(num))) ||
        cronDaysAdjusted.length === 0)
  );

  let cronTime = cronToData
    ? `${parseInt(cronToData[2], 10) < 10 ? "0" : ""}${parseInt(cronToData[2], 10)}:${
        parseInt(cronToData[1], 10) < 10 ? "0" : ""
      }${parseInt(cronToData[1], 10)}`
    : "18:00";
  //@ts-ignore
  return { selectedDays, cronTime };
}
