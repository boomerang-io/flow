import { describe, expect, it } from "vitest";
import { lastWorkspaceFrom } from "./lastWorkspace";

const withCookie = (cookie?: string) =>
  new Request("http://localhost/apps/flow/home", cookie ? { headers: { Cookie: cookie } } : undefined);

describe("lastWorkspaceFrom", () => {
  it("reads the remembered workspace among other cookies", () => {
    expect(lastWorkspaceFrom(withCookie("theme=dark; flow_last_workspace=payments; other=1"))).toBe("payments");
  });

  it("decodes the name", () => {
    expect(lastWorkspaceFrom(withCookie("flow_last_workspace=a%20b"))).toBe("a b");
  });

  it("is null when the cookie is absent, empty, a near-miss name or malformed", () => {
    expect(lastWorkspaceFrom(withCookie())).toBeNull();
    expect(lastWorkspaceFrom(withCookie("flow_last_workspacex=nope"))).toBeNull();
    expect(lastWorkspaceFrom(withCookie("flow_last_workspace="))).toBeNull();
    expect(lastWorkspaceFrom(withCookie("flow_last_workspace=%E0%A4%A"))).toBeNull();
  });
});
