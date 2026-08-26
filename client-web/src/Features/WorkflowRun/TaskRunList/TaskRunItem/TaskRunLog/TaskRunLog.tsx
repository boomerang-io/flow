import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, Theme } from "@carbon/react";
import { Toggle } from "@carbon/react";
import { ModalBody } from "@carbon/react";
import React, { Suspense, lazy } from "react";
import styles from "./taskRunLog.module.scss";
import { serviceUrl } from "Config/servicesConfig";
import { PRODUCT_SERVICE_ENV_URL } from "Config/servicesConfig";

// react-lazylog's ANSI-parsing polyfill touches `self` at module scope, which doesn't exist in
// Node (see CLAUDE.md client-web SSR rules) - genuinely SSR-infeasible, and there's no SSR value
// in it anyway (it's a live log stream). ComposedModal below only invokes its `children` render
// prop once `state.isOpen` is true (client-only, post user click, same as TextEditorModal.tsx),
// so deferring the import via `React.lazy` is enough on its own here - the dynamic import never
// fires server-side because the component is never rendered there.
const LazyLog = lazy(() => import("react-lazylog").then((mod) => ({ default: mod.LazyLog })));
const ScrollFollow = lazy(() => import("react-lazylog").then((mod) => ({ default: mod.ScrollFollow })));

const DEV_STREAM_URL =
  "https://gist.githubusercontent.com/helfi92/96d4444aa0ed46c5f9060a789d316100/raw/ba0d30a9877ea5cc23c7afcd44505dbc2bab1538/typical-live_backing.log";

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
                  <LazyLog
                    enableSearch={true}
                    fetchOptions={
                      PRODUCT_SERVICE_ENV_URL.includes("localhost") ? { credentials: "omit" } : { credentials: "include" }
                    }
                    follow={follow}
                    onScroll={onScroll}
                    onError={(err: boolean) => setError(err)}
                    selectableLines={true}
                    stream={true}
                    url={
                      PRODUCT_SERVICE_ENV_URL.includes("localhost")
                        ? DEV_STREAM_URL
                        : serviceUrl.getTaskrunLog({ id: taskrunId })
                    }
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
