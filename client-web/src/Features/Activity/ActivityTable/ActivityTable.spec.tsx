import { vi } from "vitest";
import ActivityTable from "./index";

const props = {
  activities: [],
  isUpdating: false,
  sort: {},
  tableData: { records: [], pageable: { number: 0, size: 10, sort: "asc", totalElements: 10 } },
  match: {
    params: "testid",
  },
  location: {},
  history: {},
  updateHistorySearch: vi.fn(),
};

describe("ActivityTable --- Snapshot", () => {
  it("Capturing Snapshot of ActivityTable", () => {
    const { baseElement } = global.rtlRouterRender(<ActivityTable {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
