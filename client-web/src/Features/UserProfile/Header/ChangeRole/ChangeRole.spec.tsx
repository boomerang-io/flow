import { vi } from "vitest";
import ChangeRole from ".";
import { PlatformRole, UserStatus } from "Types";
import { waitFor } from "@testing-library/react";

const user = {
  id: "1234",
  email: "test@testemail.com",
  name: "Test User",
  isFirstVisit: true,
  type: PlatformRole.Admin,
  firstLoginDate: "2020-07-21T15:35:25.369+00:00",
  lastLoginDate: "2020-07-21T15:35:25.369+00:00",
  flowTeams: [],
  status: UserStatus.Active,
  ifFirstVisit: false,
  platformRole: "admin",
};

describe("ChangeRole --- Snapshot Test", () => {
  it("Capturing Snapshot of ChangeRole", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <ChangeRole user={user} closeModal={() => vi.fn()} />
    );
    expect(baseElement).toMatchSnapshot();
    await waitFor(() => null);
  });
});
