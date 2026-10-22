import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, Theme } from "@carbon/react";
import { Toggle } from "@carbon/react";
import { ModalBody } from "@carbon/react";
import React, { Suspense, lazy } from "react";
import styles from "./taskRunLog.module.scss";
import { resourceRoute } from "Config/resourceRoutes";

// react-lazylog's ANSI-parsing polyfill touches `self` at module scope, which doesn't exist in
// Node (see CLAUDE.md client-web SSR rules) - genuinely SSR-infeasible, and there's no SSR value
// in it anyway (it's a live log stream). ComposedModal below only invokes its `children` render
// prop once `state.isOpen` is true (client-only, post user click, same as TextEditorModal.tsx),
// so deferring the import via `React.lazy` is enough on its own here - the dynamic import never
// fires server-side because the component is never rendered there.
const LazyLog = lazy(() => import("react-lazylog").then((mod) => ({ default: mod.LazyLog })));
const ScrollFollow = lazy(() => import("react-lazylog").then((mod) => ({ default: mod.ScrollFollow })));

type Props = {
  taskrunId: string;
  taskName: string;
};

export default function TaskRunLog({ taskrunId, taskName }: Props) {
  const [follow, setFollow] = React.useState(true);
  const [error, setError] = React.useState(false);

  return (
    <ComposedModal
      composedModalProps={{
        containerClassName: styles.container,
        shouldCloseOnOverlayClick: true,
      }}
      modalHeaderProps={{
        title: "Execution Log",
        label: `${taskName}`,
      }}
      modalTrigger={({ openModal }) => (
        <Button className={styles.trigger} kind="ghost" size="sm" onClick={openModal}>
          View Log
        </Button>
      )}
    >
      {() => (
        <ModalBody>
          <Suspense fallback={null}>
            <ScrollFollow
              startFollowing={true}
              render={({ onScroll }: { onScroll: () => void }) => (
                <>
                  <Theme theme="g100" className={styles.followToggle}>
                    <Toggle
                      hideLabel
                      defaultToggled={follow}
                      disabled={Boolean(error)}
                      id="task-log-toggle"
                      labelText="Follow log"
                      labelB="Follow"
                      labelA="Don't Follow"
                      onToggle={() => setFollow(!follow)}
                      toggled={follow}
                      size="sm"
                    />
                  </Theme>
                  {/*
                   * Streams the same-origin /res/taskrun/:id/log resource route
                   * (app/routes/resTaskrunLog.tsx pipes the service-core log through without
                   * buffering) instead of GET /api/v2/taskrun/{id}/log from the browser.
                   * Same-origin by construction, so the session cookie rides along with plain
                   * same-origin credentials - which also retires the old localhost special case
                   * (credentials:omit plus a public gist as a stand-in stream): the resource
                   * route works wherever the SSR server runs, dev included.
                   */}
                  <LazyLog
                    enableSearch={true}
                    fetchOptions={{ credentials: "same-origin" }}
                    follow={follow}
                    onScroll={onScroll}
                    onError={(err: boolean) => setError(err)}
                    selectableLines={true}
                    stream={true}
                    url={resourceRoute.taskrunLog({ id: taskrunId })}
                  />
                </>
              )}
            />
          </Suspense>
        </ModalBody>
      )}
    </ComposedModal>
  );
}
