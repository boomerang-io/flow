import { LineChart } from "@carbon/charts-react";
import { ChartTabularData, ScaleTypes } from "@carbon/charts/interfaces";

interface CarbonLineChartProps {
  data: ChartTabularData;
  title?: string;
}

function CarbonLineChart(props: CarbonLineChartProps) {
  const { data, title = "Line Chart" } = props;
  return (
    <LineChart
      data={data}
      options={{
        title,
        curve: "curveMonotoneX",
        height: "400px",
        axes: {
          left: {
            title: "Runs",
          },
          bottom: {
            title: "Date",
            scaleType: ScaleTypes.TIME,
          },
        },
        tooltip: {
          groupLabel: "Status",
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

export default CarbonLineChart;
