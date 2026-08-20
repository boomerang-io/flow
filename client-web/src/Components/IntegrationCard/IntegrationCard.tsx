import React, { useEffect, useRef, useState } from "react";
import { InlineLoading } from "@carbon/react";
import { CircleFill, CircleStroke, Popup } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Link, useFetcher, useRevalidator } from "react-router-dom";
import type { ActionResult } from "Features/Integrations/Integrations";
import { ModalTriggerProps } from "Types";
import ModalContent from "./ModalContent";
import styles from "./integrationCard.module.scss";

interface IntegrationCardProps {
  workspaceName: string;
  data: any;
}

const IntegrationCard: React.FC<IntegrationCardProps> = ({ workspaceName, data }) => {
  const revalidator = useRevalidator();
  const fetcher = useFetcher<ActionResult>();
  const [errorMessage, seterrorMessage] = useState(null);
  // The fetcher settles asynchronously (fetcher.state -> "idle"), so the closeModal callback
  // handed to us at submit time is stashed here and invoked from the effect below only on
  // success - the modal stays open (with the inline error banner below) on failure so the user
  // can retry, matching the previous mutateAsync/then-based behaviour. See GlobalParameters.tsx
  // for the identical pattern.
  const closeModalRef = useRef<(() => void) | null>(null);

  // Refresh the loader-driven integrations list rather than react-query's
  // queryClient.invalidateQueries - once the read is loader-driven, invalidateQueries is an
  // inert no-op (see CLAUDE.md).
  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.ok) {
      notify(
        <ToastNotification
          kind="success"
          title={`Disable Integration`}
          subtitle={`${fetcher.data.name} successfully disabled`}
        />,
      );
      revalidator.revalidate();
      closeModalRef.current?.();
      closeModalRef.current = null;
    } else {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to disable ${fetcher.data.name.toLowerCase()} failed`}
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
      //no-op
    }
  };

  const isDisabling = fetcher.state !== "idle";
  const disableError = Boolean(fetcher.data && !fetcher.data.ok);
  const disableErrorMessage = fetcher.data && !fetcher.data.ok ? fetcher.data.errorMessage : null;

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      modalHeaderProps={{
        title: `Configure ${data.name} Integration`,
        subtitle: `${data.description}`,
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <Link
          to=""
          onClick={(e: React.SyntheticEvent) => {
            e.preventDefault();
            openModal();
          }}
        >
          <div className={styles.container}>
            <section className={styles.details}>
              <div className={styles.iconContainer}>
                <img className={styles.icon} alt={`${data.name}`} src={data.icon} />
              </div>
              <div className={styles.descriptionContainer}>
                <h1 title={data.name} className={styles.name} data-testid="card-title">
                  {data.name}
                </h1>
                <p title={data.description} className={styles.description}>
                  {data.description}
                </p>
              </div>
            </section>
            <Popup size={24} className={styles.cardIcon} />
            <section className={styles.launch}></section>
            {isDisabling ? (
              <InlineLoading
                description="Loading.."
                style={{ position: "absolute", left: "0.5rem", top: "0", width: "fit-content" }}
              />
            ) : (
              <div className={styles.status}>
                {data.status === "linked" ? (
                  <TooltipHover direction="top" tooltipText="Enabled">
                    <CircleFill style={{ fill: "#009d9a", marginRight: "0.5rem" }} />
                  </TooltipHover>
                ) : (
                  <TooltipHover direction="top" tooltipText="Disabled">
                    <CircleStroke style={{ fill: "#393939", marginRight: "0.5rem" }} />
                  </TooltipHover>
                )}
              </div>
            )}
          </div>
        </Link>
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
