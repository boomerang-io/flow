import { DonutChart } from "@carbon/charts-react";
import { Alignments, ChartTabularData, LegendPositions } from "@carbon/charts/interfaces";

interface CarbonDonutChartProps {
  data: ChartTabularData;
  title?: string;
}

function CarbonDonutChart(props: CarbonDonutChartProps) {
  const { data, title = "Donut Chart" } = props;
  return (
    <div className="CarbonDonutChart">
      <DonutChart
        data={data}
        options={{
          title,
          resizable: true,
          height: "355px",
          donut: {
            alignment: Alignments.CENTER,
            center: {
              label: "Runs",
            },
          },
          legend: {
            alignment: Alignments.CENTER,
            position: LegendPositions.BOTTOM,
          },
        }}
      />
    </div>
  );
}

export default CarbonDonutChart;
