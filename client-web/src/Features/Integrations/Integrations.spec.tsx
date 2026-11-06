import { Route } from "react-router-dom";
import { screen, within } from "@testing-library/react";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { renderWithContext } from "Utils/testing/render";
import Integrations, { loader } from "./Integrations";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

function renderIntegrations() {
  return renderWithContext(<Route path="/:workspace/integrations" loader={loader} element={<Integrations />} />, {
    route: `/${WORKSPACE}/integrations`,
    workspaceValue: { workspace: workspaceFixture },
  });
}

describe("Integrations", () => {
  test("names the workspace by its display name in the breadcrumb", async () => {
    renderIntegrations();
    const breadcrumb = await screen.findByRole("navigation", { name: "Breadcrumb" });

    expect(within(breadcrumb).getByText("IBM Services Engineering")).toBeInTheDocument();
    expect(within(breadcrumb).queryByText(WORKSPACE)).not.toBeInTheDocument();
  });
});
