import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Settings", function () {
  beforeEach(() => {
    cy.visit(appLink.settings());
  });

  it("Update a section", function () {
    // cy.findByRole("heading", { name: /^Workers$/i }).click();
    cy.findAllByRole("button", { name: /save/i }).filter(":visible").should("be.disabled");
    cy.findByLabelText(/^Default Image Path$/i)
      .clear()
      .type("testing/path");
    cy.findAllByRole("button", { name: /save/i }).filter(":visible").should("be.enabled");
  });
});
