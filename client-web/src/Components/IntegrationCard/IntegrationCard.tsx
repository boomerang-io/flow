import React, { useState } from "react";
import { InlineLoading } from "@carbon/react";
import { CircleFill, CircleStroke, Popup } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useMutation, useQueryClient } from "react-query";
import { Link } from "react-router-dom";
import { resolver } from "Config/servicesConfig";
import { ModalTriggerProps } from "Types";
import ModalContent from "./ModalContent";
import styles from "./integrationCard.module.scss";

interface IntegrationCardProps {
  workspaceName: string;
  data: any;
  url: string;
}

// Each integration type unlinks through its own endpoint; keyed by the integration's display name.
const unlinkResolverByIntegrationName: Record<string, typeof resolver.postGitHubAppUnlink> = {
  GitHub: resolver.postGitHubAppUnlink,
};

const IntegrationCard: React.FC<IntegrationCardProps> = ({ workspaceName, data, url }) => {
  const queryClient = useQueryClient();
  const [errorMessage, seterrorMessage] = useState(null);

  const unlinkIntegrationMutator = useMutation(unlinkResolverByIntegrationName[data.name]);

  const handleDisable = async (closeModal: () => void) => {
    const requestBody = {
      workspace: workspaceName,
      ref: data.ref,
    };
    try {
      await unlinkIntegrationMutator.mutateAsync({ body: requestBody });
      notify(
        <ToastNotification
          kind="success"
          title={`Disable Integration`}
          subtitle={`${data.name} successfully disabled`}
        />,
      );
      queryClient.invalidateQueries(url);
      closeModal();
    } catch {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to disable ${data.name.toLowerCase()} failed`}
        />,
      );
    }
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
            {unlinkIntegrationMutator.isLoading ? (
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
          error={unlinkIntegrationMutator.error}
          handleEnable={handleEnable}
          handleDisable={handleDisable}
          errorMessage={errorMessage}
          data={data}
        />
      )}
    </ComposedModal>
  );
};

export default IntegrationCard;
