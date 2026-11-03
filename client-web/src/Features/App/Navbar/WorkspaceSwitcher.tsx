import React from "react";
import { Search, Theme } from "@carbon/react";
import { ArrowRight, Checkmark, ChevronDown } from "@carbon/react/icons";
import cx from "classnames";
import { createPortal } from "react-dom";
import { Link, useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import type { FlowWorkspaceSummary } from "Types";
import styles from "./workspaceSwitcher.module.scss";

// The list gets a filter once it is longer than a glance can take in.
const FILTER_FROM = 8;
const BUTTON_ID = "workspace-switcher-button";
const PANEL_ID = "workspace-switcher-panel";
// The shell's product name; the switcher sits right after it, where Carbon keeps product scope.
const PRODUCT_NAME_SELECTOR = '[data-testid="header-product"]';

interface WorkspaceSwitcherProps {
  workspaces: Array<FlowWorkspaceSummary>;
}

/**
 * The workspace switcher in the header: the current workspace's name as a button beside the
 * product name, opening a flat list of every workspace the user is in with the current one
 * ticked. The shell has no slot there, so it mounts itself after the product name once the
 * header is in the document; on the server it renders nothing.
 */
export default function WorkspaceSwitcher({ workspaces }: WorkspaceSwitcherProps) {
  const [host, setHost] = React.useState<HTMLElement | null>(null);

  React.useEffect(() => {
    const productName = document.querySelector(PRODUCT_NAME_SELECTOR);
    if (!productName) {
      return;
    }
    const element = document.createElement("div");
    element.className = styles.host;
    productName.insertAdjacentElement("afterend", element);
    setHost(element);
    return () => {
      element.remove();
      setHost(null);
    };
  }, []);

  if (!host) {
    return null;
  }
  return createPortal(<WorkspaceMenu workspaces={workspaces} />, host);
}

function WorkspaceMenu({ workspaces }: WorkspaceSwitcherProps) {
  const { workspace: currentName } = useParams();
  const [isOpen, setIsOpen] = React.useState(false);
  const [filter, setFilter] = React.useState("");
  const containerRef = React.useRef<HTMLDivElement>(null);
  const buttonRef = React.useRef<HTMLButtonElement>(null);
  const panelRef = React.useRef<HTMLDivElement>(null);

  const current = workspaces.find((workspace) => workspace.name === currentName);
  const sorted = [...workspaces].sort((a, b) => a.displayName.localeCompare(b.displayName));
  const query = filter.trim().toLowerCase();
  const shown = query
    ? sorted.filter((workspace) => `${workspace.displayName} ${workspace.name}`.toLowerCase().includes(query))
    : sorted;
  const hasFilter = workspaces.length > FILTER_FROM;

  const close = React.useCallback(() => {
    setIsOpen(false);
    setFilter("");
  }, []);

  // Open: focus the filter, else the current workspace, else the first. Outside click, Escape
  // and focus leaving the switcher close it; Escape hands focus back to the button.
  React.useEffect(() => {
    if (!isOpen) {
      return;
    }
    const panel = panelRef.current;
    const first =
      panel?.querySelector<HTMLElement>("input") ??
      panel?.querySelector<HTMLElement>('[aria-current="true"]') ??
      panel?.querySelector<HTMLElement>("a");
    first?.focus();

    const onMouseDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        close();
      }
    };
    // Escape closes; arrow keys walk the links, Home and End jump to the ends.
    const onKeyDown = (event: KeyboardEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        return;
      }
      if (event.key === "Escape") {
        close();
        buttonRef.current?.focus();
        return;
      }
      if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
        return;
      }
      const links = Array.from(panelRef.current?.querySelectorAll<HTMLElement>("a") ?? []);
      if (links.length === 0) {
        return;
      }
      event.preventDefault();
      const index = links.indexOf(document.activeElement as HTMLElement);
      const next =
        event.key === "Home"
          ? 0
          : event.key === "End"
          ? links.length - 1
          : event.key === "ArrowDown"
          ? Math.min(index + 1, links.length - 1)
          : Math.max(index - 1, 0);
      links[next].focus();
    };
    const onFocusOut = (event: FocusEvent) => {
      if (event.relatedTarget && !containerRef.current?.contains(event.relatedTarget as Node)) {
        close();
      }
    };
    document.addEventListener("mousedown", onMouseDown);
    document.addEventListener("keydown", onKeyDown);
    containerRef.current?.addEventListener("focusout", onFocusOut);
    const container = containerRef.current;
    return () => {
      document.removeEventListener("mousedown", onMouseDown);
      document.removeEventListener("keydown", onKeyDown);
      container?.removeEventListener("focusout", onFocusOut);
    };
  }, [isOpen, close]);

  return (
    <Theme as="div" theme="g100" className={styles.themeRoot}>
      <div className={styles.container} ref={containerRef}>
        <button
          ref={buttonRef}
          type="button"
          id={BUTTON_ID}
          className={styles.trigger}
          aria-controls={PANEL_ID}
          aria-expanded={isOpen}
          aria-label={current ? `Workspace: ${current.displayName}. Switch workspace` : "Switch workspace"}
          data-testid="workspace-switcher"
          onClick={() => (isOpen ? close() : setIsOpen(true))}
        >
          <span className={styles.label} aria-hidden="true">
            {current ? current.displayName : "Workspaces"}
          </span>
          <ChevronDown aria-hidden="true" className={cx(styles.chevron, { [styles.chevronOpen]: isOpen })} />
        </button>
        {isOpen && (
          <div
            ref={panelRef}
            id={PANEL_ID}
            className={styles.panel}
            role="region"
            aria-label="Workspaces"
            data-testid="workspace-switcher-panel"
          >
            {hasFilter && (
              <div className={styles.filter}>
                <Search
                  id="workspace-switcher-filter"
                  size="sm"
                  labelText="Find a workspace"
                  placeholder="Find a workspace"
                  value={filter}
                  onChange={(event: { target: HTMLInputElement }) => setFilter(event.target.value)}
                />
              </div>
            )}
            <ul className={styles.list}>
              {shown.map((workspace) => {
                const isCurrent = workspace.name === currentName;
                return (
                  <li key={workspace.name}>
                    <Link
                      to={appLink.workflows({ workspace: workspace.name })}
                      className={cx(styles.item, { [styles.current]: isCurrent })}
                      aria-current={isCurrent ? "true" : undefined}
                      title={workspace.displayName}
                      onClick={close}
                    >
                      <span className={styles.name}>{workspace.displayName}</span>
                      {isCurrent && <Checkmark aria-hidden="true" className={styles.tick} />}
                    </Link>
                  </li>
                );
              })}
              {shown.length === 0 && <li className={styles.empty}>No workspace matches.</li>}
            </ul>
            <Link to={appLink.home()} className={styles.footer} onClick={close}>
              All workspaces
              <ArrowRight aria-hidden="true" />
            </Link>
          </div>
        )}
      </div>
    </Theme>
  );
}
