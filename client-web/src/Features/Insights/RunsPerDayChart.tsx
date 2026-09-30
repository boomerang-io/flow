import React from "react";
import { StackedBarChart } from "@carbon/charts-react";
import { ScaleTypes } from "@carbon/charts";
import { InsightsDay } from "Types";
import { OutcomeKey, outcomeLabel, useStatusColors } from "./statusColors";

const outcomes: Array<OutcomeKey> = ["succeeded", "failed", "timedOut", "cancelled", "other"];

/**
 * Completed runs per calendar day as stacked bars, one colour per outcome. Counts are discrete,
 * so bars rather than a smoothed line; an outcome with no runs in the period stays out of the
 * legend.
 */
export default function RunsPerDayChart({ daily }: { daily: Array<InsightsDay> }) {
  const colors = useStatusColors();
  const present = outcomes.filter((outcome) => daily.some((day) => day[outcome] > 0));
  const data = daily.flatMap((day) =>
    present.map((outcome) => ({
      group: outcomeLabel[outcome],
      date: new Date(`${day.date}T00:00:00Z`),
      value: day[outcome],
    })),
  );
  const scale = Object.fromEntries(present.map((outcome) => [outcomeLabel[outcome], colors[outcome]]));

  return (
    <StackedBarChart
      data={data}
      options={{
        title: "Runs per day, by outcome",
        height: "20rem",
        axes: {
          left: { title: "Runs", stacked: true },
          bottom: { title: "Date", mapsTo: "date", scaleType: ScaleTypes.TIME },
        },
        color: { scale },
        tooltip: { groupLabel: "Outcome" },
        toolbar: { enabled: false },
        zoomBar: { top: { enabled: false } },
      }}
    />
  );
}
