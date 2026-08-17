import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Activity", function () {
  beforeEach(() => {
    cy.visit(appLink.activity());
  });

  it("Show correct filters in url", function () {
    cy.findByText(/in progress \(.+\)/i).click();
    cy.url().should("include", "statuses=inProgress");
    cy.findByText(/succeeded \(.+\)/i).click();
    cy.url().should("include", "statuses=completed");
    cy.findByText(/failed \(.+\)/i).click();
    cy.url().should("include", "statuses=failure");
    cy.findByText(/invalid \(.+\)/i).click();
    cy.url().should("include", "statuses=invalid");
    cy.findByPlaceholderText("Choose team(s)").click();
    cy.findAllByText(/^ibm services/i)
      .eq(0)
      .click();
    cy.url().should("include", "teamIds=5e3a35ad8c222700018ccd39");
    cy.findByPlaceholderText(/choose workflow\(s\)/i).click();
    cy.findAllByText(/^ml train/i)
      .eq(0)
      .click();
    cy.url().should("include", "workflowIds=5eb2c4085a92d80001a16d87");
    cy.findByPlaceholderText(/choose trigger type\(s\)/i).click();
    // cy.findByLabelText("cron").click();
    cy.findByText("Scheduler").click();
    cy.url().should("include", "triggers=scheduler");
    // cy.findByLabelText("manual").click();
    cy.findAllByText(/^manual/i)
      .eq(0)
      .click();
    // cy.findByLabelText("webhook").click();
    cy.findAllByText(/^webhook/i)
      .eq(0)
      .click();
    cy.url().should("include", "triggers=scheduler,manual,webhook");
  });

  it("Redirects to execution view", function () {
    cy.contains("Iulian Corcoja", { timeout: 5000 });
    cy.get("tbody tr").contains("Succeeded").eq(0).click();
    cy.url().should("include", "execution");
  });
});
