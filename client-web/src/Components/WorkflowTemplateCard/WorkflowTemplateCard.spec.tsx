import React from "react";
import { screen } from "@testing-library/react";
import { workflowTemplates } from "ApiServer/fixtures";
import { WorkflowStatus } from "Types";
import { renderWithContext } from "Utils/testing/render";
import WorkflowTemplateCard from "./index";

// The fixture is the wire shape returned by the mocked workflow-template list endpoint (a
// canvas-editor payload - tasks/dependencies/params in their own loosely-typed shape), which is
// not the full `Workflow` type this card's prop is typed against and WorkflowTemplateCard only
// ever reads name/description/icon off. Rather than spread the mismatched fixture and cast past
// the gap, build a minimal object that satisfies `Workflow` directly, carrying over just the
// display fields this card renders.
const templateFixture = workflowTemplates.content[0];
const props = {
  workflow: {
    name: templateFixture.name,
    displayName: templateFixture.displayName,
    creationDate: templateFixture.creationDate,
    status: WorkflowStatus.Active,
    version: templateFixture.version,
    description: templateFixture.description,
    icon: templateFixture.icon,
    labels: {},
    annotations: {},
    params: [],
    tasks: [],
    changelog: { author: "", reason: "", date: templateFixture.creationDate },
    triggers: {
      event: { enabled: false, conditions: [] },
      github: { enabled: false, conditions: [] },
      manual: { enabled: true, conditions: [] },
      schedule: { enabled: false, conditions: [] },
      webhook: { enabled: false, conditions: [] },
    },
    upgradesAvailable: false,
    workspaces: [],
  },
};

describe("WorkflowTemplateCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowTemplateCard", () => {
    const { baseElement } = renderWithContext(<WorkflowTemplateCard {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowTemplateCard --- render", () => {
  it("renders the template name and description with no management actions", () => {
    renderWithContext(<WorkflowTemplateCard {...props} />);
    expect(screen.getByTestId("workflow-card-title")).toHaveTextContent(props.workflow.name);
    expect(screen.queryByLabelText("Overflow card menu")).toBeNull();
  });
});
