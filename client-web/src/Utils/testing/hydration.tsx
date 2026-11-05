import { type ReactElement } from "react";
import { act } from "@testing-library/react";
import moment from "moment-timezone";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";

export const VIEWER_TIMEZONE = "Australia/Sydney";

/**
 * Renders `element` on a "server" in UTC, then hydrates that markup in a "browser" in Sydney -
 * what a viewer outside UTC gets from the live webapp - and returns what React had to recover
 * from. The test process runs in UTC and a worker cannot change its own timezone, so the
 * browser's is moment's default zone, which every `moment(value)` the pages format with picks up.
 * React logs each mismatch to console.error as well; mute it in the spec when that is expected.
 */
export async function hydrateInAnotherTimezone(element: ReactElement) {
  const recoverableErrors: Array<unknown> = [];
  const container = document.createElement("div");
  try {
    container.innerHTML = renderToString(element);
    const serverText = container.textContent;

    moment.tz.setDefault(VIEWER_TIMEZONE);
    document.body.appendChild(container);
    const root = await act(async () =>
      hydrateRoot(container, element, { onRecoverableError: (error) => recoverableErrors.push(error) }),
    );
    const hydratedText = container.textContent;
    act(() => root.unmount());
    return { serverText, hydratedText, recoverableErrors };
  } finally {
    moment.tz.setDefault();
    container.remove();
  }
}
