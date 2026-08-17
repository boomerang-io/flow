import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Editor -- parameters", function () {
  beforeEach(() => {
    cy.visit(appLink.editorProperties({ workflowId: "5eb2c4085a92d80001a16d87" }));
  });

  it("Edit Parameter", function () {
    cy.wait(1000);
    cy.get("[data-testid=edit-parameter-button]").eq(0).click();
    cy.findByLabelText("Default Value").type("testing default value");
    cy.get("[data-testid=parameter-modal-confirm-button]").eq(0).click();
    cy.findByText("testing default value").should("be.visible");
  });

  it("Create Parameter", function () {
    cy.wait(1000);
    cy.get("[data-testid=create-parameter-button]").click();
    cy.findByLabelText("Key").type("testingKey");
    cy.findByPlaceholderText("Select an item").click();
    cy.findByText("Boolean").click();
    cy.findByLabelText("Label").type("testing label");

    // cy.findByLabelText('Required').scrollIntoView().click();
    cy.get("[data-testid=parameter-modal-confirm-button]").eq(0).click();
    cy.findByText("testingKey").should("be.visible");
    cy.findByText("testing label").should("be.visible");
  });

  it("Delete Parameter", function () {
    cy.wait(1000);
    cy.get("[data-testid=workflow-delete-parameter-button]").eq(0).click();
    cy.get(".cds--btn--danger").click();
    cy.findByText("Tenant ID").should("not.exist");
  });
});
