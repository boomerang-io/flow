import React from "react";
import Inputs from ".";
import { screen, fireEvent } from "@testing-library/react";

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

beforeEach(() => {
  document.body.setAttribute("id", "app");
});

describe("Inputs --- Snapshot Test", () => {
  it("Capturing Snapshot of Inputs", async () => {
    const { baseElement } = rtlContextRouterRender(<Inputs {...props} />, { initialState });
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("Render inputs correctly", async () => {
    rtlContextRouterRender(<Inputs {...props} />, { initialState });
    expect(screen.getByText("tim-parameter")).toBeInTheDocument();
  });

  it("Opens create new parameter modal", async () => {
    rtlContextRouterRender(<Inputs {...props} />, { initialState });

    //expect(queryByText(/Create a new parameter/i)).not.toBeInTheDocument();

    //const modalTrigger = screen.getByText(/Create a new parameter/i);
    const modalTrigger = screen.getByTestId("create-parameter-button");
    fireEvent.click(modalTrigger);

    expect(screen.getByText(/Create a new parameter/i)).toBeInTheDocument();
  });

  it("Opens edit parameter modal", async () => {
    rtlContextRouterRender(<Inputs {...props} />, { initialState });

    //expect(queryByText(/Let's update it/i)).not.toBeInTheDocument();

    const modalTrigger = screen.getByLabelText(/Edit/i);
    fireEvent.click(modalTrigger);

    expect(screen.getByText(/Let's change some stuff/i)).toBeInTheDocument();
  });
});
