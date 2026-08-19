import { vi } from "vitest";
import { screen } from "@testing-library/react";
import WorkflowInsights from "./index";
import { startApiServer } from "ApiServer";

vi.mock("@carbon/charts-react", () => ({
  DonutChart: () => <div>DonutChart</div>,
  LineChart: () => <div>LineChart</div>,
  ScatterChart: () => <div>ScatterChart</div>,
}));

vi.mock("@carbon/charts/interfaces", () => ({
  Alignments: {},
  LegendPositions: {},
  ScaleTypes: {},
}));

let server;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("WorkflowInsights --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowInsights", async () => {
    const { baseElement } = rtlContextRouterRender(<WorkflowInsights />);
    await screen.findByTestId("completed-insights");
    // eslint-disable-next-line testing-library/no-node-access
    const a11yElement = baseElement.querySelector("#a11y-status-message");
    if (baseElement.contains(a11yElement)) {
      // eslint-disable-next-line testing-library/no-node-access
      a11yElement.parentNode.removeChild(a11yElement);
    }
    expect(baseElement).toMatchSnapshot();
  });
});
