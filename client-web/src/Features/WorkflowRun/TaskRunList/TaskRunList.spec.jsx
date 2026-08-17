import React from "react";
import { screen, fireEvent } from "@testing-library/react";
import ExecutionTaskLog from "./index";

const props = {
  workflowExecution: {
    status: "success",
    data: {
      id: "1",
      duration: 904934,
      status: "completed",
      steps: [
        {
          activityId: "5c36289096052900012cc81d",
          duration: 300190,
          
          flowTaskStatus: "completed",
          id: "5c36289096052900012cc81e",
          order: 1,
          startTime: "2019-09-03T15:00:00.230+0000",
          taskId: "d53a65a5-be1b-4e58-b0e5-0173c5aabe47",
          outputs: {
            args: {
              test: "true",
            },
            data: "",
            files: {},
            form: {},
            headers: {
              Accept: "/",
              "Accept-Encoding": "gzip,deflate",
              Connection: "close",
              "Content-Type": "text/http",
              Host: "httpbin.org",
              "User-Agent": "node-fetch/1.0 (+https://github.com/bitinn/node-fetch)",
            },
            json: null,
            method: "GET",
            origin: "147.10.60.191",
            url: "https://httpbin.org/anything?test=true",
          },
          taskName: "Slack",
        },
        {
          activityId: "5c36289096052900012cc81s",
          duration: 300190,
          flowTaskStatus: "inProgress",
          id: "5c3628909605290001dcc81e",
          order: 2,
          startTime: "2019-09-03T15:01:00.103+0000",
          taskId: "ced6eda1-47b0-4b8a-bddc-1c0bf64e0b84",
          outputs: {
            args: {
              test: "true",
            },
            data: "",
            files: {},
            form: {},
            headers: {
              Accept: "/",
              "Accept-Encoding": "gzip,deflate",
              Connection: "close",
              "Content-Type": "text/http",
              Host: "httpbin.org",
              "User-Agent": "node-fetch/1.0 (+https://github.com/bitinn/node-fetch)",
            },
            json: null,
            method: "GET",
            origin: "147.10.60.191",
            url: "https://httpbin.org/anything?test=true",
          },
          taskName: "Email",
        },
      ],
    },
  },
};

describe("ExecutionTaskLog --- Snapshot", () => {
  it("Capturing Snapshot of ExecutionTaskLog", () => {
    const { baseElement } = rtlRouterRender(<ExecutionTaskLog {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("ExecutionTaskLog --- RTL", () => {
  it("Sort tasks", () => {
    rtlRouterRender(<ExecutionTaskLog {...props} />);

    const sortButton = screen.getByTestId("taskbar-button");
    let taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems[0]).toHaveTextContent("Email");
    expect(taskItems[1]).toHaveTextContent("Slack");

    fireEvent.click(sortButton);
    taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems[0]).toHaveTextContent("Slack");
    expect(taskItems[1]).toHaveTextContent("Email");
  });
});
