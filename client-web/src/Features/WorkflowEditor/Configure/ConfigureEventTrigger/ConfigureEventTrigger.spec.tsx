import React from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import copy from "copy-to-clipboard";
import { serviceUrl } from "Config/servicesConfig";
import { renderWithRouter } from "Utils/testing/render";
import ConfigureEventTrigger from "./index";

vi.mock("copy-to-clipboard", () => ({ default: vi.fn() }));

const WORKFLOW_REF = "deploy-service";
const WORKSPACE = "payments";

/*
 * Regression spec for boomerang-io/flow#385 and #744: POST /api/v2/event reads `ref=`, not
 * `workflow=`, and a bare ref is a workflow id, which the editor does not hold; the editor's name
 * resolves only with the workspace beside it (WebhookEventControllerV2).
 */describe("ConfigureEventTrigger", () => {
  it("renders and copies the event trigger URL with ?workspace= and ?ref=, not ?workflow=", async () => {
    const { container } = renderWithRouter(
      <ConfigureEventTrigger workflowRef={WORKFLOW_REF} workspace={WORKSPACE} closeModal={() => {}} />,
    );

    // The displayed snippet appends &access_token=TOKEN — the URL parameter AuthenticationFilter accepts.
    expect(container.textContent).toContain(
      `${serviceUrl.resourceTrigger()}/event?workspace=${WORKSPACE}&ref=${WORKFLOW_REF}&access_token=TOKEN`,
    );
    expect(container.textContent).not.toContain(`event?workflow=`);

    // The copy button hands the same URL to the clipboard.
    await userEvent.click(screen.getByRole("button", { name: /copy url/i }));
    expect(copy).toHaveBeenCalledWith(`${serviceUrl.resourceTrigger()}/event?workspace=${WORKSPACE}&ref=${WORKFLOW_REF}`);
  });
});
