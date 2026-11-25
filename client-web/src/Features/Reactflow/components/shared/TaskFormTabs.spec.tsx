import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { Formik } from "formik";
import TaskFormTabs from "./TaskFormTabs";
import { TaskVersionContext, TaskVersionContextValue } from "./taskVersion";

/*
 * The header block of every task edit form: the task name first, then the tabs the task type and
 * its declared results call for. The inputs stand in for what DynamicFormik renders, keyed the
 * same way.
 */
const results = [
  { name: "output", description: "The model's reply" },
  { name: "totalTokens", description: "Tokens in and out" },
];

function renderTabs(
  props: Partial<React.ComponentProps<typeof TaskFormTabs>> = {},
  version: TaskVersionContextValue | null = null,
) {
  return render(
    <TaskVersionContext.Provider value={version}>
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
      </Formik>
    </TaskVersionContext.Provider>,
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

  it("reads a task the API sends with null results, such as the manual task, as declaring none", () => {
    renderTabs({ taskType: "manual", results: null });

    expect(screen.getByLabelText("Task name")).toBeInTheDocument();
    expect(screen.getByLabelText("Prompt")).toBeVisible();
    expect(screen.queryByRole("tab", { name: "Results" })).not.toBeInTheDocument();
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

/*
 * Every node with a task version gets the Configure tab, with the Version section first; Retry and For each stay
 * with the types that can repeat. The prompt stands in for a required param of the latest version.
 */
describe("Task form Version section", () => {
  const required = [
    { name: "prompt", label: "Prompt", required: true },
  ] as unknown as TaskVersionContextValue["params"];

  function configure() {
    fireEvent.click(screen.getByRole("tab", { name: /Configure/ }));
  }

  it("gives a type that cannot repeat a Configure tab holding the version alone", () => {
    renderTabs({ taskType: "approval" }, { taskVersion: 4, latestVersion: 5, params: [] });

    expect(tabNames()).toEqual(["Parameters", "Configure"]);
    configure();
    expect(screen.getByLabelText("Task version")).toBeVisible();
    expect(screen.queryByText("Retry")).not.toBeInTheDocument();
    expect(screen.queryByText("Run for each item")).not.toBeInTheDocument();
  });

  it("puts the version above Retry and For each on a type that can repeat", () => {
    renderTabs({ taskType: "template" }, { taskVersion: 4, latestVersion: 5, params: [] });

    configure();
    const select = screen.getByLabelText("Task version");
    const retry = screen.getByText("Retry");
    expect(select.compareDocumentPosition(retry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("Run for each item")).toBeInTheDocument();
  });

  it("offers Latest first, then each version newest first", () => {
    renderTabs({ taskType: "approval" }, { taskVersion: null, latestVersion: 3, params: [] });

    configure();
    const options = within(screen.getByLabelText("Task version")).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual(["Latest (currently: v3)", "v3", "v2", "v1"]);
  });

  it("explains each choice, and switching changes the helper text both ways", () => {
    renderTabs({ taskType: "approval" }, { taskVersion: undefined, latestVersion: 5, params: [] });
    configure();
    const select = screen.getByLabelText("Task version");

    expect(select).toHaveValue("latest");
    expect(screen.getByText("Each run uses the version that's latest when it starts.")).toBeInTheDocument();

    fireEvent.change(select, { target: { value: "4" } });
    expect(select).toHaveValue("4");
    expect(screen.getByText("Pinned: runs keep using v4 until you change it.")).toBeInTheDocument();

    fireEvent.change(select, { target: { value: "latest" } });
    expect(screen.getByText("Each run uses the version that's latest when it starts.")).toBeInTheDocument();
  });

  it("tells a pinned node behind the latest that a newer version is available, and opens the review", () => {
    const onReviewNewVersion = vi.fn();
    renderTabs({ taskType: "approval" }, { taskVersion: 4, latestVersion: 5, params: [], onReviewNewVersion });
    configure();

    expect(screen.getByText("v5 is available.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
    expect(onReviewNewVersion).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getByLabelText("Task version"), { target: { value: "5" } });
    expect(screen.queryByText("v5 is available.")).not.toBeInTheDocument();
  });

  it("warns a Latest node of a required param of the latest it leaves unset, until it is filled in", () => {
    renderTabs({ taskType: "approval" }, { taskVersion: null, latestVersion: 5, params: required });
    configure();

    const warning = "v5 needs Prompt, and this node doesn't set it yet. Fill it in on Parameters before you apply.";
    expect(screen.getByText(warning)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Task version"), { target: { value: "5" } });
    expect(screen.queryByText(warning)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Task version"), { target: { value: "latest" } });

    fireEvent.click(screen.getByRole("tab", { name: /Parameters/ }));
    fireEvent.change(screen.getByLabelText("Prompt"), { target: { value: "Summarise" } });
    configure();
    expect(screen.queryByText(warning)).not.toBeInTheDocument();
  });
});
