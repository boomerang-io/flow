import { vi } from "vitest";
import TemplateConfigModalContent from "./index";

const mockfn = vi.fn();
const mockResultParam = {
  name: "test",
  description: "test description",
  value: "test value",
};

const props = {
  closeModal: mockfn,
  forceCloseModal: mockfn,
  result: mockResultParam,
  resultKeys: [test],
  isEdit: false,
  templateFields: [mockResultParam],
  setFieldValue: mockfn,
  index: 1,
};

describe("TemplateConfigModalContent --- Snapshot", () => {
  it("Capturing Snapshot of Task Templates", async () => {
    const { baseElement } = rtlContextRouterRender(<TemplateConfigModalContent {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
