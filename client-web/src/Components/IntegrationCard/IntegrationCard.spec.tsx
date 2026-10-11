import { screen, fireEvent } from "@testing-library/react";
import { renderWithRouter } from "Utils/testing/render";
import IntegrationCard from "./IntegrationCard";

const github = {
  id: "github",
  name: "GitHub",
  description: "Integrate with GitHub and receive related events",
  icon: "https://example.com/not-loaded.png",
  instructions: "### Enable\n\nInstall the GitHub App.",
  link: "https://github.com/apps/flowabl-io/installations/select_target",
  status: "unlinked",
};

describe("IntegrationCard", () => {
  it("shows the status in words, what connecting adds, and no third-party image", () => {
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={github} />);

    expect(screen.getByText("Not connected")).toBeInTheDocument();
    expect(screen.getByText("Adds the GitHub trigger to Configure")).toBeInTheDocument();
    // The old card rendered the template's hot-linked icon as <img alt={name}>.
    expect(screen.queryByRole("img", { name: "GitHub" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Connect" })).toBeEnabled();
  });

  it("offers Manage when connected, and Disconnect inside it", async () => {
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={{ ...github, status: "linked", ref: "github-ref" }} />);

    expect(screen.getByText("Connected")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Manage" }));

    expect(await screen.findByText("Connected to this workspace")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Disconnect/, hidden: true })).toBeInTheDocument();
  });

  it("can't connect an integration the platform has no install link for", () => {
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={{ ...github, name: "Slack", link: "" }} />);

    expect(screen.getByRole("button", { name: "Connect" })).toBeDisabled();
  });
});
