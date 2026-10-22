import React from "react";
import { Route } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { vi } from "vitest";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { resourceRoute } from "Config/resourceRoutes";
import { action as resActivateAction } from "../../../../app/routes/resActivate";
import { renderWithRouter } from "Utils/testing/render";
import AppActivation from "./AppActivation";

// The activation submit must flow through the /res/activate route action (which makes the
// service-core PUT via serverFetch), never a direct browser PUT /api/v2/activate. In a jsdom
// spec both paths would hit the same MSW /api handler, so the discriminator is the action
// itself: it is spy-wrapped and attached to the test router at resourceRoute.activateAction() -
// a component that still PUTs /api directly never invokes it and fails these tests.
function renderAppActivation(setActivationCode = vi.fn()) {
  const actionSpy = vi.fn(resActivateAction);
  renderWithRouter(
    <>
      <Route path={resourceRoute.activateAction()} action={actionSpy} />
      <Route path="*" element={<AppActivation setActivationCode={setActivationCode} />} />
    </>,
  );
  return { actionSpy, setActivationCode };
}

describe("AppActivation --- RTL", () => {
  it("submits the code through the route action and hands it back on success", async () => {
    const { actionSpy, setActivationCode } = renderAppActivation();

    fireEvent.change(screen.getByLabelText("Activation code"), { target: { value: "otc-123" } });
    fireEvent.click(screen.getByText("Submit"));

    await waitFor(() => expect(setActivationCode).toHaveBeenCalledWith("otc-123"));
    expect(actionSpy).toHaveBeenCalledTimes(1);
  });

  it("surfaces the upstream error body via the action result without activating", async () => {
    server.use(
      http.put(serviceUrl.putActivationApp(), () =>
        HttpResponse.json({ status: "401", error: "Invalid one-time code" }, { status: 401 }),
      ),
    );
    const { actionSpy, setActivationCode } = renderAppActivation();

    fireEvent.change(screen.getByLabelText("Activation code"), { target: { value: "wrong-code" } });
    fireEvent.click(screen.getByText("Submit"));

    // formatErrorMessage builds "status - error" from the upstream body the action passes through.
    expect(await screen.findByText("401 - Invalid one-time code")).toBeInTheDocument();
    expect(screen.getByText("Try again?")).toBeInTheDocument();
    expect(actionSpy).toHaveBeenCalledTimes(1);
    expect(setActivationCode).not.toHaveBeenCalled();
  });
});
