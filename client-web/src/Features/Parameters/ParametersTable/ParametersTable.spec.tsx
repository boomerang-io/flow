import { screen, within } from "@testing-library/react";
import { renderWithRouter } from "Utils/testing/render";
import { PASSWORD_CONSTANT } from "Constants";
import type { DataDrivenInput } from "Types";
import ParametersTable from "./ParametersTable";

const parameters = [
  { name: "github-token", label: "GitHub token", type: "password", value: "*****", readOnly: true },
  { name: "legacy", label: "Legacy", type: "text", value: "kept", readOnly: true },
  { name: "mine", label: "Mine", type: "text", value: "editable" },
] as unknown as Array<DataDrivenInput>;

describe("ParametersTable", () => {
  it("names the integration managing a read-only parameter, and calls any other read-only", () => {
    renderWithRouter(
      <ParametersTable
        scope="workspace"
        parameters={parameters}
        managedBy={{ "github-token": "GitHub" }}
        onAdd={() => {}}
        onEdit={() => {}}
        onDelete={() => {}}
      />,
    );

    expect(screen.getByText("Managed by GitHub")).toBeInTheDocument();
    expect(screen.getByText("Read-only")).toBeInTheDocument();
    // Every row keeps Copy reference; only the editable row offers Edit and Delete.
    expect(screen.getAllByRole("button", { name: "Copy reference" })).toHaveLength(3);
    expect(screen.getAllByRole("button", { name: "Edit" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Delete" })).toHaveLength(1);
  });

  it("keeps a secured value in its table cell, in line with the rest of the row", () => {
    const secured = [{ name: "wssecret", type: "password", value: "hunter2" }] as unknown as Array<DataDrivenInput>;
    renderWithRouter(
      <ParametersTable scope="workspace" parameters={secured} onAdd={() => {}} onEdit={() => {}} onDelete={() => {}} />,
    );

    expect(screen.queryByText("hunter2")).not.toBeInTheDocument();
    const row = screen.getAllByRole("row")[1];
    expect(within(row).getByText("Secured")).toBeInTheDocument();
    const value = within(row).getByText(PASSWORD_CONSTANT);
    const cell = within(row)
      .getAllByRole("cell")
      .find((candidate) => candidate.textContent === PASSWORD_CONSTANT)!;
    // The value's own block, truncating class stays on the value: on the cell it pulls the cell
    // out of the table's row layout.
    expect(value.className).not.toBe("");
    expect(cell).not.toHaveClass(value.className);
    expect(screen.getByRole("columnheader", { name: "Value" })).not.toHaveClass(value.className);
  });
});
