import { screen } from "@testing-library/react";
import { renderWithRouter } from "Utils/testing/render";
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
});
