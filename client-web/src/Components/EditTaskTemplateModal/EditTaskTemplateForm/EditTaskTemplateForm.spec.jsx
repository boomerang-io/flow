import { vi } from "vitest";
import EditTaskTemplateForm from "./index";

const mockfn = vi.fn();
const mockRevision = {
  name: "Test name",
  arguments: ["slack"],
  changelog: {
    userId: "1234",
    reason: "",
    date: "2021-03-19T23:34:09.258+00:00",
    userName: "Test User",
  },
  description: "Test description",
  command: [],
  config: [
    {
      required: false,
      placeholder: "https://hooks.slack.com/services/...",
      defaultValue: "",
      readOnly: false,
      description: "Found within your webhook integration settings",
      key: "url",
      label: "URL",
      type: "text",
      helperText: "",
    },
  ],
  envs: [{ name: "test", value: "TEST" }],
  image: "",
  results: null,
  script: null,
  version: 1,
  workingDir: null,
};
const props = {
  closeModal: mockfn,
  handleEditTaskTemplateModal: mockfn,
  nodeType: "customTask",
  taskTemplates: [mockRevision],
  templateData: mockRevision,
};

describe("EditTaskTemplateForm --- Snapshot", () => {
  it("Capturing Snapshot of Task Templates", async () => {
    const { baseElement } = rtlContextRouterRender(<EditTaskTemplateForm {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
