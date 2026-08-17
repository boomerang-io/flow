import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("TaskTemplates", function () {
  beforeEach(() => {
    cy.visit(appLink.taskTemplates());
  });

  it("Select a task", function () {
    cy.get("[data-testid=task-templates-search]").type("artifactory download");
    cy.contains("artifactory (1)").click();
    cy.contains("Artifactory File Download").click();
    cy.url().should("include", "/5bd9b4115a5df954ad5ad8db");
  });
});
