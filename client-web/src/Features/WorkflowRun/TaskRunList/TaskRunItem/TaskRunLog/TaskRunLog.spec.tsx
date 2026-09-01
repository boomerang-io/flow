import React from "react";
import { http, HttpResponse } from "msw";
import { fireEvent, render, screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import TaskExecutionLog from "./index";

// The modal body is a render prop ComposedModal only invokes once open, and inside it the log
// surface is behind React.lazy/Suspense (see TaskRunLog.tsx) - so a spec that only snapshots the
// closed modal asserts nothing but the trigger button. Open it and wait for the lazily imported
// react-lazylog surface to mount.
const props = {
  taskrunId: "2",
  taskName: "Send Slack Message",
};

beforeEach(() => {
  // LazyLog streams the taskrun log the moment it mounts; without this the request is
  // unhandled and MSW warns.
  server.use(
    http.get(serviceUrl.getTaskrunLog({ id: ":id" }), () => HttpResponse.text("line one\nline two\n")),
  );
});

describe("TaskRunLog --- RTL", () => {
  it("renders the log surface for a taskrun once the modal is opened", async () => {
    render(<TaskExecutionLog {...props} />);

    fireEvent.click(screen.getByText("View Log"));

    // The modal header proves the modal opened...
    expect(await screen.findByText("Execution Log")).toBeInTheDocument();
    expect(screen.getByText(props.taskName)).toBeInTheDocument();
    // ...and both of these come from inside the Suspense boundary, so they can only appear if
    // the lazily imported react-lazylog surface actually mounted: the follow toggle is rendered
    // by ScrollFollow's render prop, the grid is LazyLog's own virtualised log viewport.
    // `hidden: true` because react-modal marks the whole <body> aria-hidden while a modal is
    // open (see the `cds--bmrg-body-modal-is-open` body class), which would otherwise exclude
    // every element in the tree from a role query.
    expect(await screen.findByRole("switch", { hidden: true, name: /Follow log/ })).toBeInTheDocument();
    expect(await screen.findByRole("grid", { hidden: true })).toBeInTheDocument();
  });
});
