import React, { useEffect, useRef, useState } from "react";
import { Button, InlineLoading } from "@carbon/react";
import { CheckmarkFilled, Connect, LogoGithub, LogoSlack } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useFetcher } from "react-router-dom";
import type { ActionResult } from "Features/Integrations/Integrations";
import { isActionError } from "Utils/actionResult";
import ModalContent from "./ModalContent";
import styles from "./integrationCard.module.scss";

interface IntegrationCardProps {
  workspaceName: string;
  data: any;
}

// Carbon's logos rather than the template's icon URL, which pointed at third-party sites. What connecting
// each one adds, in a line per capability; an integration not listed here shows its description alone.
const known: Record<string, { Logo: React.ComponentType<any>; adds: Array<string> }> = {
  github: {
    Logo: LogoGithub,
    adds: ["Adds the GitHub trigger to Configure", "Starts workflows from events on chosen repositories"],
  },
  slack: {
    Logo: LogoSlack,
    adds: ["A slash command to run a workflow", "Approve or reject actions from a message"],
  },
};

const IntegrationCard: React.FC<IntegrationCardProps> = ({ workspaceName, data }) => {
  const fetcher = useFetcher<ActionResult>();
  const [errorMessage, seterrorMessage] = useState(null);
  // The fetcher settles asynchronously (fetcher.state -> "idle"), so the closeModal callback
  // handed to us at submit time is stashed here and invoked from the effect below only on
  // success - the modal stays open (with the inline error banner below) on failure so the user
  // can retry. See GlobalParameters.tsx for the identical pattern.
  const closeModalRef = useRef<(() => void) | null>(null);

  // The integrations list is loader-driven: React Router revalidates every matched loader
  // automatically once this fetcher's action settles, so there is nothing to invalidate or
  // revalidate by hand here.
  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (!isActionError(fetcher.data)) {
      notify(
        <ToastNotification kind="success" title="Disconnected" subtitle={`${fetcher.data.name} is disconnected`} />,
      );
      closeModalRef.current?.();
      closeModalRef.current = null;
    } else {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to disconnect ${fetcher.data.name} failed`}
        />,
      );
    }
  }, [fetcher.state, fetcher.data]);

  const handleDisable = (closeModal: () => void) => {
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: "disconnect", name: data.name, workspace: workspaceName, ref: data.ref }, { method: "post" });
  };

  const handleEnable = async (closeModal: () => void) => {
    try {
      window.open(data.link, "_blank");
      closeModal();
    } catch (err) {
      seterrorMessage(
        formatErrorMessage({
          error: err,
          defaultMessage: "Enable integration failed",
        }),
      );
    }
  };

  const isConnected = data.status === "linked";
  const isDisabling = fetcher.state !== "idle";
  const disableError = Boolean(fetcher.data && isActionError(fetcher.data));
  const disableErrorMessage = fetcher.data && isActionError(fetcher.data) ? fetcher.data.error : null;
  const { Logo, adds } = known[String(data.name).toLowerCase()] ?? { Logo: Connect, adds: [] };
  const canConnect = isConnected || Boolean(data.link);

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      modalHeaderProps={{
        label: "Integration",
        title: data.name,
      }}
      modalTrigger={({ openModal }) => (
        <section className={styles.container} aria-label={data.name}>
          <div className={styles.details}>
            <Logo size={32} aria-hidden="true" className={styles.logo} />
            <div>
              <h2 className={styles.name} data-testid="card-title">
                {data.name}
              </h2>
              {isDisabling ? (
                <InlineLoading description="Disconnecting…" />
              ) : isConnected ? (
                <p className={styles.connected}>
                  <CheckmarkFilled aria-hidden="true" /> Connected
                </p>
              ) : (
                <p className={styles.notConnected}>Not connected</p>
              )}
            </div>
          </div>
          <p className={styles.description}>{data.description}</p>
          {adds.length > 0 && (
            <ul className={styles.adds}>
              {adds.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          )}
          <div className={styles.actions}>
            <Button
              disabled={!canConnect}
              kind={isConnected ? "tertiary" : "primary"}
              onClick={openModal}
              size="sm"
              title={canConnect ? undefined : "Not set up on this platform"}
            >
              {isConnected ? "Manage" : "Connect"}
            </Button>
          </div>
        </section>
      )}
    >
      {({ closeModal }) => (
        <ModalContent
          closeModal={closeModal}
          error={disableError}
          handleEnable={handleEnable}
          handleDisable={handleDisable}
          errorMessage={errorMessage ?? disableErrorMessage}
          data={data}
        />
      )}
    </ComposedModal>
  );
};

export default IntegrationCard;
