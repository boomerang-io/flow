/* eslint-disable */
import { vi } from "vitest";
import Settings from "./Settings";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Response } from "miragejs";
import { startApiServer } from "ApiServer";
import { serviceUrl } from "Config/servicesConfig";

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("Settings --- Snapshot", () => {
  test("Capturing Snapshot of Settings", () => {
    const { baseElement } = global.rtlContextRouterRender(<Settings />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Settings --- RTL", () => {
  test("Loads and opens section", async () => {
    global.rtlContextRouterRender(<Settings />);
    expect(screen.getByRole("heading", { name: /^Settings$/i })).toBeInTheDocument();
    const section = await screen.findByRole("heading", { name: "Workers" });
    fireEvent.click(section);
    expect(screen.getByLabelText(/^Enable Debug$/i)).toBeInTheDocument();
  });
});
describe("Settings --- RTL", () => {
  beforeEach(() => {
    server.get(serviceUrl.resourceSettings(), () => {
      return new Response(500, {}, {});
    });
  });
  test("Shows error message on request failure", async () => {
    global.rtlContextRouterRender(<Settings />);
    expect(screen.getByRole("heading", { name: /^Settings$/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText("Oops, something went wrong.")).toBeInTheDocument();
    });
  });
});
