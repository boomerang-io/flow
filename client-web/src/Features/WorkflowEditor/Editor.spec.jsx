import { vi } from "vitest";
import Editor from "./index";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { startApiServer } from "ApiServer";
import { db } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  window.focus = vi.fn();
  server = startApiServer();
  server.db.loadData(db);
});

afterEach(() => {
  server.shutdown();
});

describe("Editor --- Snapshot", () => {
  it("Capturing Snapshot of Editor", async () => {
    const { baseElement } = rtlContextRouterRender(
      <Route path={AppPath.EditorCanvas}>
        <Editor />
      </Route>,
      { route: appLink.editorCanvas({ workflowId: "5eb2c4085a92d80001a16d87" }) }
    );
    await screen.findByText("Editor");
    expect(baseElement).toMatchSnapshot();
  });
});
