import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { TokenType } from "Constants";
import { globalTokensLoader, tokenAction, workflowTokensLoader } from "Components/TokenSection/tokenRoute";
import { renderWithContext } from "Utils/testing/render";
import CreateServiceTokenButton from "../CreateToken";

function renderGlobal() {
  return renderWithContext(
    <Route
      path="*"
      loader={globalTokensLoader}
      action={tokenAction}
      element={<CreateServiceTokenButton type={TokenType.Global} principal="**" />}
    />,
  );
}

// Captures the body of the next POST /token so a test can read what the form sent.
function captureCreateRequest() {
  const captured: { body?: Record<string, unknown> } = {};
  server.use(
    http.post(serviceUrl.postToken(), async ({ request }) => {
      captured.body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: "t1", type: "global", token: "bfg_test" }, { status: 201 });
    }),
  );
  return captured;
}

async function fillAndSubmit() {
  userEvent.type(await screen.findByLabelText(/^Name$/i), "my-test-token");
  const submit = screen.getByTestId("create-token-submit");
  await waitFor(() => expect(submit).toBeEnabled());
  userEvent.click(submit);
  expect(await screen.findByText(/Token successfully created/i)).toBeInTheDocument();
}

describe("CreateToken form --- actor kind on a global token", () => {
  it("offers Service, Agent and None, with Service selected", async () => {
    renderGlobal();
    userEvent.click(await screen.findByTestId("create-token-button"));

    expect(await screen.findByLabelText(/Service/)).toBeChecked();
    expect(screen.getByLabelText(/Agent/)).not.toBeChecked();
    expect(screen.getByLabelText(/None/)).not.toBeChecked();
    // Only the three choices exist: a workflow token is minted from the editor, never offered here.
    expect(screen.getAllByRole("radio", { hidden: true })).toHaveLength(3);
    expect(screen.getByText("A system, script or dispatcher")).toBeInTheDocument();
    expect(screen.getByText("An AI agent")).toBeInTheDocument();
  });

  it("sends SERVICE by default", async () => {
    const captured = captureCreateRequest();
    renderGlobal();
    userEvent.click(await screen.findByTestId("create-token-button"));
    await fillAndSubmit();

    expect(captured.body).toMatchObject({ type: "global", actorKind: "SERVICE" });
  });

  it("sends AGENT when Agent is chosen", async () => {
    const captured = captureCreateRequest();
    renderGlobal();
    userEvent.click(await screen.findByTestId("create-token-button"));
    userEvent.click(await screen.findByLabelText(/Agent/));
    await fillAndSubmit();

    expect(captured.body).toMatchObject({ actorKind: "AGENT" });
  });

  it("sends no actorKind when None is chosen", async () => {
    const captured = captureCreateRequest();
    renderGlobal();
    userEvent.click(await screen.findByTestId("create-token-button"));
    userEvent.click(await screen.findByLabelText(/None/));
    await fillAndSubmit();

    expect(captured.body).toBeDefined();
    expect(captured.body).not.toHaveProperty("actorKind");
  });

  it("does not offer the choice on a workspace token", async () => {
    renderWithContext(
      <Route
        path="*"
        loader={workflowTokensLoader}
        action={tokenAction}
        element={<CreateServiceTokenButton type={TokenType.Key} principal="test-workspace" />}
      />,
    );
    userEvent.click(await screen.findByTestId("create-token-button"));
    await screen.findByLabelText(/^Name$/i);
    expect(screen.queryByLabelText(/Service/)).not.toBeInTheDocument();
  });
});
