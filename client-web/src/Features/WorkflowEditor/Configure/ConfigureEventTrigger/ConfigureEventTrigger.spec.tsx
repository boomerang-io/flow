import React from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import copy from "copy-to-clipboard";
import { serviceUrl } from "Config/servicesConfig";
import { renderWithRouter } from "Utils/testing/render";
import ConfigureEventTrigger from "./index";

vi.mock("copy-to-clipboard", () => ({ default: vi.fn() }));

const WORKFLOW_REF = "5eb2c4085a92d80001a16d87";

/*
 * Regression spec for boomerang-io/flow#385: the editor rendered the copyable event trigger
 * URL with a `workflow=` query parameter, but POST /api/v2/event only reads `ref=`
 * (WebhookEventControllerV2), so the displayed URL never targeted the workflow.
 */
describe("ConfigureEventTrigger", () => {
  it("renders and copies the event trigger URL with ?ref=, not ?workflow=", async () => {
    const { container } = renderWithRouter(
      <ConfigureEventTrigger workflowRef={WORKFLOW_REF} closeModal={() => {}} />,
    );

    // The displayed snippet appends &access_token=TOKEN — the URL parameter AuthenticationFilter accepts.
    expect(container.textContent).toContain(
      `${serviceUrl.resourceTrigger()}/event?ref=${WORKFLOW_REF}&access_token=TOKEN`,
    );
    expect(container.textContent).not.toContain(`event?workflow=`);

    // The copy button hands the same ?ref= URL to the clipboard.
    await userEvent.click(screen.getByRole("button", { name: /copy url/i }));
    expect(copy).toHaveBeenCalledWith(`${serviceUrl.resourceTrigger()}/event?ref=${WORKFLOW_REF}`);
  });
});
