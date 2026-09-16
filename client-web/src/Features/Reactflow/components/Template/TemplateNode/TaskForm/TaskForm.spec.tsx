import React from "react";
import { screen } from "@testing-library/react";
import { aiTask } from "ApiServer/fixtures";
import { renderWithContext } from "Utils/testing/render";
import type { Task, WorkflowNodeData } from "Types";
import TaskForm from "./TaskForm";

// The seeded `ai` task exercises the whole generic path in one go: text, password, text-editor,
// number, select and the new slider all come off the catalogue entry with no bespoke code.
const task = aiTask as unknown as Task;

const node = {
  name: "Ask the model",
  taskRef: "ai",
  taskVersion: 1,
  upgradesAvailable: false,
  params: [],
  results: [],
} as unknown as WorkflowNodeData;

function renderTaskForm() {
  return renderWithContext(
    <TaskForm
      availableParameters={[]}
      closeModal={() => {}}
      node={node}
      onSave={() => {}}
      otherTaskNames={[]}
      task={task}
    />,
  );
}

describe("Task config form --- the seeded ai task", () => {
  it("renders every catalogue parameter by its label, with no task-specific code", () => {
    renderTaskForm();

    expect(screen.getByLabelText("Task Name")).toBeInTheDocument();
    expect(screen.getByLabelText("Endpoint")).toBeInTheDocument();
    expect(screen.getByLabelText("Model")).toBeInTheDocument();
    expect(screen.getByLabelText("Max Tokens")).toBeInTheDocument();
    expect(screen.getByText("Response Format")).toBeInTheDocument();
  });

  it("masks the token parameter", () => {
    renderTaskForm();

    expect(screen.getByLabelText("Token")).toHaveAttribute("type", "password");
  });

  it("renders the temperature parameter as a slider carrying the catalogue's bounds", () => {
    renderTaskForm();

    const slider = screen.getByRole("slider");
    expect(slider).toHaveAttribute("aria-valuemin", "0");
    expect(slider).toHaveAttribute("aria-valuemax", "2");
    // The default arrives as the string "0.7" off the catalogue entry.
    expect(screen.getByRole("spinbutton", { name: "Temperature" })).toHaveValue(0.7);
  });

  it("lists the six results the task produces", () => {
    renderTaskForm();

    for (const result of ["output", "promptTokens", "completionTokens", "totalTokens", "finishReason", "model"]) {
      expect(screen.getByText(new RegExp(`^${result}:`))).toBeInTheDocument();
    }
  });
});
