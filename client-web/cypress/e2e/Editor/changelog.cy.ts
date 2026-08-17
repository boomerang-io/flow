import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Editor -- changelog", function () {
  beforeEach(() => {
    cy.visit(appLink.editorChangelog({ workflowId: "5eb2c4085a92d80001a16d87" }));
  });

  it("Search does not exist", function () {
    cy.wait(1000);
    cy.get("[data-testid=change-log-search]").type("testing filter");
    cy.findByText("No change logs found").should("be.visible");
  });

  it("Search exist", function () {
    cy.wait(1000);
    cy.get("[data-testid=change-log-search]").type("sir");
    cy.findByText("hello sir").should("be.visible");
  });
});
