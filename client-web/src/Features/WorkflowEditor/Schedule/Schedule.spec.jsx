import React from "react";
import Schedule from "./index";

const initialState = {};

const props = {
  summaryData: {
    properties: [
      {
        defaultValue: "pandas",
        description: "Tim parameter",
        key: "tim-parameter",
        label: "Tim parameter",
        required: true,
        type: "select",
        optiions: ["pandas", "dogs"],
      },
    ],
    id: "123",
  },
};

describe("Schedule --- Snapshot Test", () => {
  it("Capturing Snapshot of Inputs", async () => {
    const { baseElement } = rtlContextRouterRender(<Schedule {...props} />, { initialState });
    expect(baseElement).toMatchSnapshot();
  });
});
