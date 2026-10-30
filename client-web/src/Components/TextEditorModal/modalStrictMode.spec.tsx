import React, { StrictMode } from "react";
import { render } from "@testing-library/react";
import ReactModal from "react-modal";
import { Modal } from "@boomerang-io/carbon-addons-boomerang-react";

/*
 * The webapp hydrates under StrictMode (app/entry.client.tsx), which mounts, unmounts and mounts
 * every component once in development. react-modal hid the app on each mount of an open modal but
 * undid it only once, so after the last dialog closed the app stayed aria-hidden and every role
 * query, and every screen reader, saw nothing in it. Patched in patches/react-modal@3.16.1.patch.
 */
describe("a modal under StrictMode", () => {
  let app: HTMLElement;

  beforeEach(() => {
    app = document.body.appendChild(document.createElement("div"));
    app.id = "app";
    ReactModal.setAppElement(app);
  });

  afterEach(() => {
    app.remove();
  });

  function Dialogs({ open, nested = false }: { open: boolean; nested?: boolean }) {
    return (
      <StrictMode>
        <Modal isOpen={open} closeTimeoutMS={0}>
          <p>outer</p>
        </Modal>
        <Modal isOpen={open && nested} closeTimeoutMS={0}>
          <p>inner</p>
        </Modal>
      </StrictMode>
    );
  }

  test("hides the app while open and shows it again once closed", () => {
    const view = render(<Dialogs open />);
    expect(app).toHaveAttribute("aria-hidden", "true");

    view.rerender(<Dialogs open={false} />);

    expect(app).not.toHaveAttribute("aria-hidden");
    expect(document.documentElement.className).not.toContain("modal-is-open");
    expect(document.body.className).not.toContain("modal-is-open");
    view.unmount();
  });

  test("a modal opened over another leaves the app visible once both close", () => {
    const view = render(<Dialogs open nested />);
    expect(app).toHaveAttribute("aria-hidden", "true");

    view.rerender(<Dialogs open />);
    expect(app).toHaveAttribute("aria-hidden", "true");

    view.rerender(<Dialogs open={false} />);
    expect(app).not.toHaveAttribute("aria-hidden");
    view.unmount();
  });

  test("a modal unmounted while open shows the app again", () => {
    const view = render(<Dialogs open />);
    expect(app).toHaveAttribute("aria-hidden", "true");

    view.unmount();

    expect(app).not.toHaveAttribute("aria-hidden");
    expect(document.body.className).not.toContain("modal-is-open");
  });
});
