import React from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import copy from "copy-to-clipboard";
import { serviceUrl } from "Config/servicesConfig";
import { renderWithRouter } from "Utils/testing/render";
import BuildWebhookModalContent from "./index";

vi.mock("copy-to-clipboard", () => ({ default: vi.fn() }));

const WORKFLOW_REF = "5eb2c4085a92d80001a16d87";

/*
 * Regression spec for boomerang-io/flow#385: the editor rendered the copyable webhook trigger
 * URL with a `workflow=` query parameter, but POST /api/v2/webhook only reads `ref=`
 * (WebhookEventControllerV2), so pasting the displayed URL returned a 400.
 */
describe("BuildWebhookModalContent", () => {
  it("renders and copies the webhook trigger URL with ?ref=, not ?workflow=", async () => {
    const { container } = renderWithRouter(
      <BuildWebhookModalContent workflowRef={WORKFLOW_REF} closeModal={() => {}} />,
    );

    // The displayed snippet (the template literal wraps before &access_token=, so match textContent).
    expect(container.textContent).toContain(`${serviceUrl.resourceTrigger()}/webhook?ref=${WORKFLOW_REF}`);
    expect(container.textContent).not.toContain(`webhook?workflow=`);

    // The copy button hands the same ?ref= URL to the clipboard.
    await userEvent.click(screen.getByRole("button", { name: /copy url/i }));
    expect(copy).toHaveBeenCalledWith(`${serviceUrl.resourceTrigger()}/webhook?ref=${WORKFLOW_REF}`);
  });
});
