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

  it("offers to share the installation token with tasks once connected, showing whether it is shared", async () => {
    const fields = [{ name: "github-token" }, { name: "github-url" }];
    renderWithRouter(
      <IntegrationCard workspaceName="cheer" data={{ ...github, status: "linked", ref: "github-ref", fields, shared: true }} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Manage" }));

    const share = await screen.findByRole("switch", { name: /Share the installation token with tasks/, hidden: true });
    expect(share).toBeChecked();
    expect(
      screen.getByText(/Every workflow author in this workspace can then act with the GitHub App's permissions/),
    ).toBeInTheDocument();
    expect(screen.getByText(/\$\(workspace\.params\.github-token\) and \$\(workspace\.params\.github-url\)/)).toBeInTheDocument();
  });

  it("has no share switch for an integration with nothing to share", async () => {
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={{ ...github, status: "linked", ref: "github-ref" }} />);

    fireEvent.click(screen.getByRole("button", { name: "Manage" }));

    expect(await screen.findByText("Connected to this workspace")).toBeInTheDocument();
    expect(screen.queryByRole("switch", { hidden: true })).not.toBeInTheDocument();
  });

  it("offers to share Slack's bot token with tasks once connected", async () => {
    const slack = {
      ...github,
      id: "slack",
      name: "Slack",
      link: "https://slack.com/oauth/v2/authorize?client_id=1234&scope=chat:write&state=signed",
      status: "linked",
      ref: "slack-ref",
      fields: [{ name: "slack-token" }],
      shared: false,
    };
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={slack} />);

    expect(screen.getByText("Lets the Slack tasks use the app's bot token")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Manage" }));

    const share = await screen.findByRole("switch", { name: /Share the installation token with tasks/, hidden: true });
    expect(share).not.toBeChecked();
    expect(screen.getByText(/Tasks read it as \$\(workspace\.params\.slack-token\)\./)).toBeInTheDocument();
  });

  it("can't connect an integration the platform has no install link for", () => {
    renderWithRouter(<IntegrationCard workspaceName="cheer" data={{ ...github, name: "Slack", link: "" }} />);

    expect(screen.getByRole("button", { name: "Connect" })).toBeDisabled();
  });
});
