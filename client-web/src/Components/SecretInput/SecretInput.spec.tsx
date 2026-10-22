import React from "react";
import userEvent from "@testing-library/user-event";
import { render, screen } from "@testing-library/react";
import { REDACTED } from "Constants";
import SecretInput, { CLEARED_HELPER, REFERENCE_HELPER, SET_HELPER } from "./SecretInput";

// The field holds what the API reads a secret as and what it will send: the marker to keep the
// stored secret, "" to clear it, or the new text. The marker itself is never shown or typed into.
function Harness({ initial, isSet, suggestReference }: { initial: string; isSet: boolean; suggestReference?: boolean }) {
  const [value, setValue] = React.useState(initial);
  return (
    <>
      <SecretInput
        id="secret"
        labelText="Token"
        data-testid="secret"
        isSet={isSet}
        value={value}
        onChange={setValue}
        suggestReference={suggestReference}
      />
      <output data-testid="sent">{JSON.stringify(value)}</output>
    </>
  );
}

const sent = () => JSON.parse(screen.getByTestId("sent").textContent ?? "null");

describe("SecretInput", () => {
  test("a stored secret shows as set, empty, and is sent back as the marker", () => {
    render(<Harness initial={REDACTED} isSet />);

    expect(screen.getByTestId("secret")).toHaveValue("");
    expect(screen.getByText(SET_HELPER)).toBeInTheDocument();
    expect(sent()).toBe(REDACTED);
  });

  test("typing replaces the secret rather than appending to the marker", async () => {
    render(<Harness initial={REDACTED} isSet />);

    await userEvent.type(screen.getByTestId("secret"), "new-token");

    expect(sent()).toBe("new-token");
  });

  test("deleting what was typed over a stored secret keeps it again", async () => {
    render(<Harness initial={REDACTED} isSet />);

    await userEvent.type(screen.getByTestId("secret"), "x");
    await userEvent.clear(screen.getByTestId("secret"));

    expect(sent()).toBe(REDACTED);
  });

  test("Clear sends a blank, and Undo clear keeps the stored secret", async () => {
    render(<Harness initial={REDACTED} isSet />);

    await userEvent.click(screen.getByTestId("secret-clear"));
    expect(sent()).toBe("");
    expect(screen.getByText(CLEARED_HELPER)).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("secret-undo"));
    expect(sent()).toBe(REDACTED);
  });

  test("an unset secret has no Clear control and suggests a reference when asked", () => {
    render(<Harness initial="" isSet={false} suggestReference />);

    expect(screen.queryByTestId("secret-clear")).not.toBeInTheDocument();
    expect(screen.getByText(REFERENCE_HELPER)).toBeInTheDocument();
  });
});
