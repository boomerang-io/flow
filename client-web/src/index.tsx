//@ts-nocheck
import { render } from "react-dom";
import Root from "./Root";
import { Envs } from "Constants";
import "Config/axiosGlobalConfig";
import "Styles/styles.scss";

(async () => {
  if (window.Cypress) {
    const { Server, Response } = await import("miragejs");
    new Server({
      environment: "test",
      routes() {
        let methods = ["get", "put", "patch", "post", "delete"];
        methods.forEach((method) => {
          this[method]("/*", async (_, request) => {
            let [status, headers, body] = await window.handleFromCypress(request);
            return new Response(status, headers, body);
          });
        });
      },
    });
  } else {
    // if (import.meta.env.MODE === Envs.Dev && import.meta.env.MODE !== Envs.PortForward) {
    //   const { startApiServer } = await import("./ApiServer");
    //   startApiServer({ environment: "development", timing: 400 });
    // }
  }

  // Setup hot module reloading to improve dev experience
  render(<Root />, document.getElementById("app"));

  // If you want your app to work offline and load faster, you can change
  // unregister() to register() below. Note this comes with some pitfalls.
  // Learn more about service workers: http://bit.ly/CRA-PWA
  //serviceWorker.unregister();
})();
