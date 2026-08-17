import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Global parameters", function () {
  beforeEach(() => {
    cy.visit(appLink.properties());
  });

  it("Find parameters", function () {
    cy.findByText("Test parameter").should("be.visible");
    cy.findByText("test.key").should("be.visible");
    cy.findByText("adding a test description").should("be.visible");
  });

  it("Create a Global parameter", function () {
    cy.get("[data-testid=create-global-parameter-button]").click();
    cy.get("[data-testid=create-parameter-key]").type("newKey");
    cy.get("[data-testid=create-parameter-label]").type("new label");
    cy.get("[data-testid=create-parameter-description]").type("new description");
    cy.get("[data-testid=create-parameter-value]").type("new value");
    cy.get("[data-testid=global-parameter-create-submission-button]").click();
    cy.wait(1000);
    cy.findByText("new label").should("be.visible");
    cy.findByText("newKey").should("be.visible");
    cy.findByText("new description").should("be.visible");
    cy.findByText("new value").should("be.visible");
  });

  it("Delete a Global Parameter", function () {
    cy.get("[data-testid=configuration-parameter-table-overflow-menu]").eq(0).click();
    cy.contains("Delete").click();
    cy.get(".cds--btn--danger").click();
    cy.findByText("test label").should("not.exist");
    cy.findByText("test.key").should("not.exist");
    cy.findByText("for testing purpose").should("not.exist");
  });
});
