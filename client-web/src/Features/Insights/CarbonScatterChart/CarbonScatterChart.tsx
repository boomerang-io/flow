import { ScatterChart } from "@carbon/charts-react";
import { ScaleTypes, ChartTabularData } from "@carbon/charts/interfaces";
import "@carbon/charts/styles.css";

interface CarbonScatterChartProps {
  data: ChartTabularData;
  title: string;
}

function CarbonScatterChart(props: CarbonScatterChartProps) {
  const { data, title = "Scatter Chart" } = props;
  return (
    <ScatterChart
      data={data}
      options={{
        title,
        height: "400px",
        axes: {
          left: {
            title: "Duration (secs)",
            mapsTo: "value",
          },
          bottom: {
            title: "Date",
            scaleType: ScaleTypes.TIME,
            mapsTo: "date",
          },
        },
        legend: {
          enabled: false,
        },
        tooltip: {
          groupLabel: "Workflow",
          truncation: {
            numCharacter: 50,
          },
        },
        zoomBar: {
          top: {
            enabled: false,
          },
        },
      }}
    />
  );
}

export default CarbonScatterChart;
