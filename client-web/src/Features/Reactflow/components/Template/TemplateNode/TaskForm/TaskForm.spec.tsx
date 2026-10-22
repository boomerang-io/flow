import React from "react";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { aiTask } from "ApiServer/fixtures";
import { renderWithContext } from "Utils/testing/render";
import type { Task, WorkflowNodeData } from "Types";
import { splitForeachValues } from "../../../shared/foreach";
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
      nodeType="ai"
      onSave={() => {}}
      otherTaskNames={[]}
      task={task}
    />,
  );
}

describe("Task config form --- the seeded ai task", () => {
  it("renders every catalogue parameter by its label, with no task-specific code", () => {
    renderTaskForm();

    expect(screen.getByLabelText("Task name")).toBeInTheDocument();
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

  it("lists the six results the task produces, by name and description, on the Results tab", () => {
    renderTaskForm();

    fireEvent.click(screen.getByRole("tab", { name: "Results" }));
    const list = screen.getByRole("list", { name: "Results" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(6);
    for (const result of task.spec.results!) {
      expect(within(list).getByText(result.name)).toBeInTheDocument();
      expect(within(list).getByText(result.description)).toBeInTheDocument();
    }
  });
});

// The four required ai params filled in, so the form is valid and Apply is enabled.
const validNode = {
  ...node,
  params: [
    { name: "endpoint", value: "https://models.example.com" },
    { name: "token", value: "secret" },
    { name: "model", value: "a-model" },
    { name: "prompt", value: "Summarise $(params.item)" },
  ],
} as unknown as WorkflowNodeData;

function renderTabbedTaskForm(overrides: Partial<React.ComponentProps<typeof TaskForm>> = {}) {
  return renderWithContext(
    <TaskForm
      availableParameters={[]}
      closeModal={() => {}}
      node={validNode}
      nodeType="ai"
      onSave={() => {}}
      otherTaskNames={[]}
      task={task}
      {...overrides}
    />,
  );
}

describe("Task config form --- Parameters and Configure tabs", () => {
  it("keeps the task name above the tabs, editable, and opens on Parameters", () => {
    renderTabbedTaskForm();

    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual(["Parameters", "Configure", "Results"]);
    expect(tabs[0]).toHaveAttribute("aria-selected", "true");
    const name = screen.getByLabelText("Task name");
    expect(name).toBeVisible();
    expect(name.compareDocumentPosition(tabs[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.change(name, { target: { value: "Ask again" } });
    expect(name).toHaveValue("Ask again");
    expect(screen.queryByText("Specifics")).not.toBeInTheDocument();
  });

  it("shows Items and the For each tag once Run for each item is on", async () => {
    renderTabbedTaskForm();

    fireEvent.click(screen.getByRole("tab", { name: "Configure" }));
    expect(screen.queryByLabelText("Items")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("switch"));

    expect(await screen.findByLabelText("Items")).toBeInTheDocument();
    expect(within(screen.getByRole("tab", { name: /Configure/ })).getByText("For each")).toBeInTheDocument();
    expect(screen.getByText(/is the current item/)).toBeInTheDocument();
  });

  it("marks the Configure tab with an error icon while Items is invalid", async () => {
    renderTabbedTaskForm();

    fireEvent.click(screen.getByRole("tab", { name: "Configure" }));
    fireEvent.click(screen.getByRole("switch"));
    fireEvent.change(await screen.findByLabelText("Items"), { target: { value: "not a list" } });

    expect(await screen.findByLabelText("Configure has an error")).toBeInTheDocument();
    expect(screen.queryByLabelText("Parameters has an error")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Items"), { target: { value: "$(tasks.stage.results.batches)" } });
    await waitFor(() => expect(screen.queryByLabelText("Configure has an error")).not.toBeInTheDocument());
  });

  it("rejects [ and ] in the task name, which name the items of a for-each task", async () => {
    renderTabbedTaskForm();

    fireEvent.change(screen.getByLabelText("Task name"), { target: { value: "Ask the model[0]" } });

    expect(await screen.findByText("Task names cannot end in [ and a number ]; that form is reserved for for-each items")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
  });

  it("marks the Parameters tab with an error icon when a required parameter is empty", async () => {
    renderTaskForm();

    expect(await screen.findByLabelText("Parameters has an error")).toBeInTheDocument();
    expect(screen.queryByLabelText("Configure has an error")).not.toBeInTheDocument();
  });

  it("saves the for-each items with the params on Apply", async () => {
    const onSave = vi.fn();
    renderTabbedTaskForm({ onSave });

    fireEvent.click(screen.getByRole("tab", { name: "Configure" }));
    fireEvent.click(screen.getByRole("switch"));
    fireEvent.change(await screen.findByLabelText("Items"), { target: { value: '["a", "b"]' } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Apply" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    const values = onSave.mock.calls[0][0];
    expect(values).toMatchObject({ foreachEnabled: true, foreachItems: '["a", "b"]', model: "a-model" });
    expect(splitForeachValues(values).foreach).toEqual({ items: ["a", "b"] });
  });

  it("saves the params without the task's read-only results", async () => {
    const onSave = vi.fn();
    renderTabbedTaskForm({ onSave });

    await waitFor(() => expect(screen.getByRole("button", { name: "Apply" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    // One argument only: the template's declared results are not the node's to overwrite.
    expect(onSave.mock.calls[0]).toHaveLength(1);
    const values = onSave.mock.calls[0][0];
    expect(values).toMatchObject({ taskName: "Ask the model", model: "a-model" });
    expect(values).not.toHaveProperty("results");
  });

  it("loads a saved for-each setting back into the Configure tab", async () => {
    renderTabbedTaskForm({ node: { ...validNode, foreach: { items: "$(params.repos)" } } });

    expect(within(screen.getByRole("tab", { name: /Configure/ })).getByText("For each")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: /Configure/ }));
    expect(await screen.findByLabelText("Items")).toHaveValue("$(params.repos)");
  });
});

describe("Task config form --- which task types can run for each item", () => {
  // The same catalogue entry with nothing declared as a result.
  const taskWithoutResults = { ...task, spec: { ...task.spec, results: [] } } as Task;

  it.each(["decision", "approval", "sleep", "runworkflow"])(
    "shows a %s task's name, then Parameters and Results with no Configure",
    (nodeType) => {
      renderTabbedTaskForm({ nodeType });

      expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Parameters", "Results"]);
      expect(screen.queryByText("Run for each item")).not.toBeInTheDocument();
      expect(screen.getByLabelText("Task name")).toBeVisible();
      expect(screen.getByLabelText("Model")).toBeVisible();
    },
  );

  it("shows a task that can neither run for each item nor declares results as its name above its inputs", () => {
    renderTabbedTaskForm({ nodeType: "approval", task: taskWithoutResults });

    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Task name")).toBeVisible();
    expect(screen.getByLabelText("Model")).toBeVisible();
  });

  it.each(["template", "script", "custom", "ai", "generic"])("offers the Configure tab on a %s task", (nodeType) => {
    renderTabbedTaskForm({ nodeType, task: taskWithoutResults });

    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Parameters", "Configure"]);
  });
});
