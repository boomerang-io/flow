import React from "react";
import { render, screen } from "@testing-library/react";
import { DataDrivenInput } from "Types";
import WorkflowInputModalContent from "./WorkflowInputModalContent";

function renderInputs(inputs: Array<DataDrivenInput>) {
  return render(
    <WorkflowInputModalContent
      closeModal={vi.fn()}
      executeError={null}
      executeWorkflow={vi.fn()}
      inputs={inputs}
      isExecuting={false}
      errorMessage={null}
    />,
  );
}

describe("WorkflowInputModalContent", () => {
  it("labels a parameter with no label by its name, and one with a label by that label", () => {
    renderInputs([
      { name: "region", type: "text", label: "" } as DataDrivenInput,
      { name: "env", type: "text", label: "Environment" } as DataDrivenInput,
      { name: "count", type: "number" } as DataDrivenInput,
    ]);

    expect(screen.getByLabelText("region")).toBeInTheDocument();
    expect(screen.getByLabelText("Environment")).toBeInTheDocument();
    expect(screen.getByLabelText("count")).toBeInTheDocument();
    expect(screen.queryByLabelText("env")).not.toBeInTheDocument();
  });
});
