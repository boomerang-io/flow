import React from "react";
import { fireEvent, screen, within } from "@testing-library/react";
import { aiTask, task as taskFixture } from "ApiServer/fixtures";
import { taskIcons } from "Utils/taskIcons";
import { renderWithContext } from "Utils/testing/render";
import type { Task } from "Types";
import Tasks from "./Tasks";

// One non-AI task so the palette has a second category to group against, plus the seeded `ai`
// entry. The full fixture catalogue is deliberately not used - Editor.spec.tsx's snapshot owns
// that list, and this spec is about grouping, not about the catalogue's size.
const otherTask = taskFixture.content[0];
// The fixture modules are plain .js, so TS infers their literal shape rather than `Task` (the
// changelog fixture is an object where the type is a list, and several optional fields are
// absent). The palette only reads name/displayName/category/icon/version/verified/scope.
const paletteTasks = [otherTask, aiTask] as unknown as Array<Task>;

describe("Editor task palette --- the AI task", () => {
  it("groups the seeded ai task under its own AI category", async () => {
    renderWithContext(<Tasks tasks={paletteTasks} />);

    // The accordion header carries the category name and its task count.
    const aiCategory = await screen.findByText("AI (1)");
    expect(aiCategory).toBeInTheDocument();
    expect(screen.getByText(`${otherTask.category} (1)`)).toBeInTheDocument();
  });

  it("renders the ai task as a draggable palette option named by its display name", async () => {
    renderWithContext(<Tasks tasks={paletteTasks} />);

    const option = await screen.findByRole("option", { name: aiTask.displayName });
    expect(option).toHaveAttribute("draggable", "true");
    // The tile's icon comes from the task's `icon` key; "AI" must resolve in the icon map or the
    // palette silently falls back to the generic Bee.
    expect(within(option).getByText(aiTask.displayName)).toBeInTheDocument();
  });

  it("offers AI in the palette's filter-by-task-type list", () => {
    // The filter list is built straight off the icon map, so the AI entry has to exist there for
    // the seed's `icon: "AI"` to resolve at all.
    expect(taskIcons.find((icon) => icon.name === "AI")).toBeDefined();
    expect(aiTask.icon).toBe("AI");
    expect(aiTask.category).toBe("AI");
    expect(aiTask.type).toBe("ai");
  });
});

describe("Editor task palette --- For each", () => {
  it("offers For each first under the workflow category, draggable with the for-each marker", async () => {
    renderWithContext(<Tasks tasks={paletteTasks} />);

    // Neither palette task is in the workflow category, so For each opens one of its own.
    expect(await screen.findByText("Workflow (1)")).toBeInTheDocument();
    const option = screen.getByRole("option", { name: "For each" });
    expect(option).toHaveAttribute("draggable", "true");

    const setData = vi.fn();
    fireEvent.dragStart(option, { dataTransfer: { setData, effectAllowed: "" } });
    expect(setData).toHaveBeenCalledWith("application/reactflow", expect.stringContaining('"type":"foreach"'));
  });

  it("follows the search box", async () => {
    renderWithContext(<Tasks tasks={paletteTasks} />);

    fireEvent.change(await screen.findByPlaceholderText("Search for a task"), { target: { value: "each" } });
    expect(screen.getByRole("option", { name: "For each" })).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("Search for a task"), { target: { value: aiTask.displayName } });
    expect(screen.queryByRole("option", { name: "For each" })).not.toBeInTheDocument();
  });
});
