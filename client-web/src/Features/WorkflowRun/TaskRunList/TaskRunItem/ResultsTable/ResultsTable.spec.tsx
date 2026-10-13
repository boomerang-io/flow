import React from "react";
import { render, screen } from "@testing-library/react";
import ResultsTable from "./index";

describe("ResultsTable", () => {
  it("shows a JSON value indented", () => {
    render(<ResultsTable data={[{ key: "output", value: '{"state":"ATTENTION","count":10}' }]} />);

    expect(screen.getByText(/"state": "ATTENTION",\s+"count": 10/)).toBeInTheDocument();
  });

  it("shows any other value as it is", () => {
    render(<ResultsTable data={[{ key: "model", value: "google/gemini-2.5-flash-lite" }]} />);

    expect(screen.getByText("google/gemini-2.5-flash-lite")).toBeInTheDocument();
  });
});
