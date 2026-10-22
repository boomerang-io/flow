import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { Formik } from "formik";
import TaskFormTabs from "./TaskFormTabs";

/*
 * The header block of every task edit form: the task name first, then the tabs the task type and
 * its declared results call for. The inputs stand in for what DynamicFormik renders, keyed the
 * same way.
 */
const results = [
  { name: "output", description: "The model's reply" },
  { name: "totalTokens", description: "Tokens in and out" },
];

function renderTabs(props: Partial<React.ComponentProps<typeof TaskFormTabs>> = {}) {
  return render(
    <Formik initialValues={{ taskName: "ask", prompt: "" }} onSubmit={() => {}}>
      {(formikProps) => (
        <TaskFormTabs
          formikProps={formikProps}
          inputs={[
            <label key="taskName">
              Task name
              <input name="taskName" value={formikProps.values.taskName} onChange={formikProps.handleChange} />
            </label>,
            <label key="prompt">
              Prompt
              <input name="prompt" value={formikProps.values.prompt} onChange={formikProps.handleChange} />
            </label>,
          ]}
          {...props}
        />
      )}
    </Formik>,
  );
}

function tabNames() {
  return screen.getAllByRole("tab").map((tab) => tab.textContent);
}

describe("Task form header block", () => {
  it("renders the name field before the tab list, and the name stays editable", () => {
    renderTabs({ taskType: "template", results });

    const name = screen.getByLabelText("Task name");
    const tabList = screen.getByRole("tablist", { name: "Task configuration" });
    expect(name.compareDocumentPosition(tabList) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.change(name, { target: { value: "ask again" } });
    expect(name).toHaveValue("ask again");
  });

  it("adds a Results tab only when the task declares results, listing name and description", () => {
    const { unmount } = renderTabs({ taskType: "template", results });

    expect(tabNames()).toEqual(["Parameters", "Configure", "Results"]);
    fireEvent.click(screen.getByRole("tab", { name: "Results" }));
    const list = screen.getByRole("list", { name: "Results" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).getByText("output")).toBeInTheDocument();
    expect(within(list).getByText("The model's reply")).toBeInTheDocument();
    expect(within(list).getByText("totalTokens")).toBeInTheDocument();
    expect(within(list).getByText("Tokens in and out")).toBeInTheDocument();
    unmount();

    renderTabs({ taskType: "template", results: [] });
    expect(tabNames()).toEqual(["Parameters", "Configure"]);
  });

  it("gives a type without Configure the name block and, with results, Parameters and Results", () => {
    renderTabs({ taskType: "approval", results });

    expect(screen.getByLabelText("Task name")).toBeInTheDocument();
    expect(tabNames()).toEqual(["Parameters", "Results"]);
    expect(screen.getByLabelText("Prompt")).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "Results" }));
    expect(screen.getByRole("list", { name: "Results" })).toBeVisible();
  });

  it("gives a type without Configure or results the name alone above its inputs", () => {
    renderTabs({ taskType: "approval" });

    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    const name = screen.getByLabelText("Task name");
    const prompt = screen.getByLabelText("Prompt");
    expect(name.compareDocumentPosition(prompt) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
