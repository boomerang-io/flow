import React from "react";
import { http, HttpResponse } from "msw";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { vi } from "vitest";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import WorkspaceCreateContent from "./WorkspaceCreateContent";

const defaultProps = {
  closeModal: vi.fn(),
  createWorkspace: vi.fn(),
  isLoading: false,
  isError: false,
};

// The slice's goal made assertable: the browser half of this component must never call /api/*.
// Its name-availability probe goes through the same-origin /res/workspace/validate-name resource
// route (mocked by the shared handlers off Config/resourceRoutes.ts's builders); the direct
// /api/v2 validate-name POST it used to make is force-failed here, so any regression back to a
// direct browser call turns "available" into TAKEN and fails these tests.
beforeEach(() => {
  server.use(http.post(serviceUrl.postWorkspaceValidateName(), () => HttpResponse.error()));
});

describe("WorkspaceCreateContent --- RTL", () => {
  test("an available name validates through the resource route (direct /api blocked) and enables Create", async () => {
    render(<WorkspaceCreateContent {...defaultProps} />);

    const nameInput = screen.getByLabelText(/^Display Name$/i);
    // One change event, not per-keystroke typing - see Workspaces.spec.tsx's note on racing
    // overlapping probes.
    fireEvent.change(nameInput, { target: { value: "Fresh Workspace" } });

    expect(await screen.findByText(/fresh-workspace/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/^Create$/i)).toBeEnabled());
    expect(screen.queryByText(/is unavailable/i)).not.toBeInTheDocument();

    // Same settle-poll as Workspaces.spec.tsx documents: the async probe settles over several
    // render passes (and Formik re-runs it on submit before calling onSubmit), so clicking on
    // every enabled sighting until the submit lands is the race-free shape - a click on a
    // disabled button is a no-op in jsdom.
    for (let attempt = 0; attempt < 40; attempt++) {
      if (defaultProps.createWorkspace.mock.calls.length > 0) break;
      const createButton = screen.queryByText(/^Create$/i);
      if (createButton && !createButton.hasAttribute("disabled")) {
        fireEvent.click(createButton);
      }
      // eslint-disable-next-line no-await-in-loop
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    expect(defaultProps.createWorkspace).toHaveBeenCalledWith({ name: "Fresh Workspace" }, defaultProps.closeModal);
  });

  test("a taken name (fixture workspace tyson-workspace) reads as unavailable and keeps Create disabled", async () => {
    render(<WorkspaceCreateContent {...defaultProps} />);

    fireEvent.change(screen.getByLabelText(/^Display Name$/i), { target: { value: "Tyson Workspace" } });

    expect(await screen.findByText(/the name 'Tyson Workspace' is unavailable/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/^Create$/i)).toBeDisabled());
  });

  // The name shown is the one the server takes: non-Latin letters and punctuation drop out, digits
  // stay where they are, and a display name that leaves nothing says so.
  test("previews the server's form of the name, and says when nothing is left of it", async () => {
    render(<WorkspaceCreateContent {...defaultProps} />);
    const nameInput = screen.getByLabelText(/^Display Name$/i);

    fireEvent.change(nameInput, { target: { value: "Ops 开发 team2!" } });
    expect(await screen.findByText("ops-team2")).toBeInTheDocument();

    fireEvent.change(nameInput, { target: { value: "开发团队" } });
    expect(await screen.findByText(/nothing for the web address/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/^Create$/i)).toBeDisabled());
  });
});
