import { vi } from "vitest";
import { renderWithRouter } from "Utils/testing/render";
import ActivityTable from "./index";

const props = {
  isLoading: false,
  sort: "",
  order: "ASC",
  tableData: { number: 0, size: 10, sort: "asc", totalElements: 10, content: [] },
  location: {},
  navigate: vi.fn(),
  updateHistorySearch: vi.fn(),
  workflowNameMap: {},
};

describe("ActivityTable --- Snapshot", () => {
  it("Capturing Snapshot of ActivityTable", () => {
    const { baseElement } = renderWithRouter(<ActivityTable {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
