import React from "react";
import { startApiServer } from "ApiServer";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { TokenType } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import CreateServiceTokenButton from "./CreateToken";

let server: any;
const getTokensUrl = serviceUrl.getTokens({ query: "" });

beforeEach(() => {
  document.body.setAttribute("id", "app");
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("CreateServiceTokenButton --- Snapshot", () => {
  it("Capturing Snapshot of CreateServiceTokenButton", async () => {
    const { baseElement } = global.rtlQueryRender(<CreateServiceTokenButton type={TokenType.Workspace} getTokensUrl={getTokensUrl} />);
    await screen.findByText(/Create Token/i);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("CreateServiceTokenButton --- RTL", () => {
  it("Open token creation modal", async () => {
    global.rtlQueryRender(<CreateServiceTokenButton type={TokenType.Workspace} getTokensUrl={getTokensUrl} />);
    const button = screen.getByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create Workspace Token/i)).not.toBeInTheDocument();
    userEvent.click(button);
    expect(screen.getByText(/Create Workspace Token/i)).toBeInTheDocument();
  });

  it("Fill out form", async () => {
    global.rtlQueryRender(<CreateServiceTokenButton type={TokenType.Workspace} getTokensUrl={getTokensUrl} />);
    const button = screen.getByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create Workspace Token/i)).not.toBeInTheDocument();
    userEvent.click(button);

    expect(screen.getByText(/Create Workspace Token/i)).toBeInTheDocument();

    const descriptionInput = screen.getByTestId("token-description");
    userEvent.type(descriptionInput, "Token test description");

    const createButton = screen.getByTestId(/create-token-submit/i);

    expect(createButton).toBeEnabled();
    userEvent.click(createButton);
    expect(await screen.findByText(/Workspace token successfully created/i)).toBeInTheDocument();
  });
});
