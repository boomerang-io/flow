import React from "react";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { profile } from "ApiServer/fixtures";
import { TokenType } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import CreateServiceTokenButton from "./CreateToken";

const getTokensUrl = serviceUrl.getTokens({ query: "" });
// The app populates AppContext.workspaces from the flat user.teams array
// (see Features/App/App.tsx) -- not the paginated workspaces query response.
const contextValue = { workspaces: profile.teams };

describe("CreateServiceTokenButton --- Snapshot", () => {
  it("Capturing Snapshot of CreateServiceTokenButton", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <CreateServiceTokenButton type={TokenType.Key} getTokensUrl={getTokensUrl} />,
      { contextValue }
    );
    await screen.findByText(/Create Token/i);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("CreateServiceTokenButton --- RTL", () => {
  it("Open token creation modal", async () => {
    global.rtlContextRouterRender(<CreateServiceTokenButton type={TokenType.Key} getTokensUrl={getTokensUrl} />, {
      contextValue,
    });
    const button = screen.getByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create new token/i)).not.toBeInTheDocument();
    userEvent.click(button);
    expect(screen.getByText(/Create new token/i)).toBeInTheDocument();
  });

  it("Fill out form", async () => {
    global.rtlContextRouterRender(<CreateServiceTokenButton type={TokenType.Key} getTokensUrl={getTokensUrl} />, {
      contextValue,
    });
    const button = screen.getByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create new token/i)).not.toBeInTheDocument();
    userEvent.click(button);

    expect(screen.getByText(/Create new token/i)).toBeInTheDocument();

    const nameInput = screen.getByLabelText(/^Name$/i);
    userEvent.type(nameInput, "my-test-token");

    const descriptionInput = screen.getByTestId("token-description");
    userEvent.type(descriptionInput, "Token test description");

    const createButton = screen.getByTestId(/create-token-submit/i);

    expect(createButton).toBeEnabled();
    userEvent.click(createButton);
    expect(await screen.findByText(/Token successfully created/i)).toBeInTheDocument();
  });
});
