import React from "react";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { task as taskFixture } from "ApiServer/fixtures";
import { groupTasksByName } from "Utils";
import { renderWithContext } from "Utils/testing/render";
import type { Task } from "Types";
import ForeachModal from "./ForeachModal";

// The fixture catalogue mixes template, custom and control types (setwfstatus, ...).
const catalogue = taskFixture.content as unknown as Array<Task>;
const tasks = groupTasksByName(catalogue);
const httpTask = catalogue.find((task) => task.displayName === "Execute Advanced HTTP Call") as Task;

function openModal(overrides: Partial<React.ComponentProps<typeof ForeachModal>> = {}) {
  const props = {
    isOpen: true,
    tasks,
    takenNames: ["Start", "End"],
    suggestName: (task: Task) => task.displayName,
    onCancel: vi.fn(),
    onSubmit: vi.fn(),
    ...overrides,
  };
  renderWithContext(<ForeachModal {...props} />);
  return props;
}

describe("Repeat a task for each item", () => {
  // One flow per render: rendering ComposedModal a second time in a fresh test in the same file
  // renders nothing under jsdom (see ScheduleCreator.spec.tsx for the same quirk).
  it("offers the dispatcher's task types, names the pick, and hands back task, items and name", async () => {
    const handlers = openModal();

    expect(await screen.findByText("Repeat a task for each item")).toBeInTheDocument();

    // Cancel only reports back; the parent closes the modal and creates nothing.
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(handlers.onCancel).toHaveBeenCalled();

    fireEvent.click(screen.getByRole("combobox"));
    const offered = screen.getAllByRole("option").map((option) => option.textContent);
    expect(offered).toContain("Execute Advanced HTTP Call");
    expect(offered).toContain("Run Custom Task");
    expect(offered).not.toContain("Set Workflow Result Status");

    fireEvent.click(screen.getByRole("option", { name: httpTask.displayName }));
    expect(screen.getByLabelText("Task Name")).toHaveValue(httpTask.displayName);

    const next = screen.getByRole("button", { name: "Next: task parameters" });
    fireEvent.change(screen.getByLabelText("Items"), { target: { value: "not a list" } });
    await waitFor(() => expect(next).toBeDisabled());
    fireEvent.change(screen.getByLabelText("Items"), { target: { value: "$(params.urls)" } });
    await waitFor(() => expect(next).toBeEnabled());
    fireEvent.click(next);

    await waitFor(() =>
      expect(handlers.onSubmit).toHaveBeenCalledWith({
        task: httpTask,
        items: "$(params.urls)",
        taskName: httpTask.displayName,
      }),
    );
    expect(handlers.onSubmit).toHaveBeenCalledTimes(1);
  });
});
