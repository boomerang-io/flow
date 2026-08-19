import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Team Parameters", function () {
  beforeEach(() => {
    cy.visit(appLink.teamProperties());
  });

  it("Filter by team and find parameters", function () {
    cy.get("[data-testid=team-parameters-combobox]").click();
    cy.contains("IBM Services Engineering").click();
    cy.findByText("test label").should("be.visible");
    cy.findByText("test key").should("be.visible");
    cy.findByText("for testing purpose").should("be.visible");
  });

  it("Create a team parameter", function () {
    cy.get("[data-testid=team-parameters-combobox]").click();
    cy.contains("IBM Services Engineering").click();
    cy.get("[data-testid=create-team-parameter-button]").click();
    cy.findByLabelText("Key").type("newKey");
    cy.findByLabelText("Label").type("new label");
    cy.findByLabelText("Description (optional)").type("new description");
    cy.findByLabelText("Value").type("new value");
    cy.get("[data-testid=team-parameter-create-edit-submission-button]").click();
    cy.wait(1000);
    cy.findByText("new label").should("be.visible");
    cy.findByText("newKey").should("be.visible");
    cy.findByText("new description").should("be.visible");
    cy.findByText("new value").should("be.visible");
  });

  it("Delete a team parameter", function () {
    cy.get("[data-testid=team-parameters-combobox]").click();
    cy.contains("IBM Services Engineering").click();
    cy.findAllByTestId("team-parameter-menu-button").first().click();
    cy.contains("Delete").click();
    cy.get(".cds--btn--danger").click();
    cy.findByText("test label").should("not.exist");
    cy.findByText("test key").should("not.exist");
    cy.findByText("for testing purpose").should("not.exist");
  });
});
