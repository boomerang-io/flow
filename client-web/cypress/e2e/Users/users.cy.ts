import { startApiServer } from "ApiServer";
// import { appLink } from "Config/appConfig";

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

/**
 * todo: need to be able to set PRODUCT_STANDALONE on the window on visit
 */

// describe("TeamProperties", function () {
//   beforeEach(() => {
//     cy.visit(appLink.manageUsers());
//   });

//   it("Navigates to users admin feature", function () {
//     cy.findByText("View and manage Flow users").should("be.visible");
//     cy.findByPlaceholderText("Search users").should("be.visible");
//     cy.findByText("Marcus Roy").should("be.visible");
//   });

//   it("Changes url on sort and search", function () {
//     cy.findByPlaceholderText("Search users").type("mdroy@us.ibm.com");
//     cy.findByText("Marcus Roy").should("be.visible");
//     cy.findByText("Tim Bula").should("not.be.visible");
//   });

//   it("Views more user detail", function () {
//     cy.findAllByLabelText("Menu", { role: "button" }).first().click();
//     cy.findByText(/view details/i).click();
//     cy.findByText("# of Flow Teams").should("be.visible");
//   });

//   it("Change user role", function () {
//     cy.findAllByLabelText("Menu", { role: "button" }).first().click();
//     cy.findByText(/change role/i).click();
//     cy.findByText(/^User$/).click();
//     cy.findByText("Submit").click();
//     cy.findByText(/^user$/i).should("be.visible");
//   });
// });
