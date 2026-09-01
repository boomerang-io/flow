import React from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithContext } from "Utils/testing/render";
import PreviewConfig from "./index";

const props = {
  taskTemplateName: "Test template",
  templateConfig: [
    {
      placeholder: "",
      readOnly: false,
      description: "",
      key: "path",
      label: "File Path",
      type: "text",
    },
    {
      placeholder: "",
      readOnly: false,
      description: "",
      key: "propertyName",
      label: "Property Name",
      type: "text",
    },
  ],
};

describe("PreviewConfig --- Snapshot", () => {
  it("Capturing Snapshot of Task Templates", async () => {
    const { baseElement } = renderWithContext(<PreviewConfig {...props} />);
    fireEvent.click(screen.getByText("Preview"));
    expect(baseElement).toMatchSnapshot();
  });
});

