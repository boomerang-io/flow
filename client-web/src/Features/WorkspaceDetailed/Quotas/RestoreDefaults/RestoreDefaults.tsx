import {
  ComposedModal,
  ModalForm,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { Reset } from "@carbon/react/icons";
import React from "react";
import { useFetcher, useLoaderData } from "react-router-dom";
import styles from "./RestoreDefaults.module.scss";
import { ModalTriggerProps, FlowWorkspace } from "Types";
import type { QuotasActionResult, QuotasLoaderData } from "../Quotas";

interface RestoreDefaultsProps {
  workspace: FlowWorkspace;
  disabled: boolean;
}

const RestoreDefaults: React.FC<RestoreDefaultsProps> = ({ workspace, disabled }) => {
  return (
    <ComposedModal
      composedModalProps={{
        containerClassName: styles.modalContainer,
      }}
      modalHeaderProps={{
        title: "Restore defaults",
        subtitle: "This will change all quotas to the following default values. This action cannot be undone.",
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <Button className={styles.resetButton} size="md" renderIcon={Reset} onClick={openModal} disabled={disabled}>
          Restore defaults
        </Button>
      )}
    >
      {({ closeModal }) => <RestoreModalContent closeModal={closeModal} />}
    </ComposedModal>
  );
};

interface restoreDefaultProps {
  closeModal: Function;
}

const RestoreModalContent: React.FC<restoreDefaultProps> = ({ closeModal }) => {
  // The default quotas come from the Quotas route's loader now (see ../Quotas) rather than a
  // useQuery that only started once this modal opened - so there is no in-modal loading state
  // left to render.
  const { defaultQuotas, errorLoadingDefaults } = useLoaderData() as QuotasLoaderData;
  // Posts to that same route's action. Its completion revalidates the parent Manage Workspace
  // loader that holds the displayed quota values - nothing refreshed them before (a pre-existing
  // gap: the page kept showing the old quotas until reloaded).
  const fetcher = useFetcher<QuotasActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const failed = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent === "restore");

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "restore") {
      return;
    }
    if (fetcher.data.ok) {
      closeModal();
      notify(
        <ToastNotification
          kind="success"
          title="Restore Default Quotas"
          subtitle="Successfully restored default quotas"
        />,
      );
    } else {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle="Failed to restore default quotas" />);
    }
  }, [fetcher.state, fetcher.data, closeModal]);

  const handleRestoreDefaultQuota = () => {
    fetcher.submit({ intent: "restore" }, { method: "post" });
  };

  let buttonText = "Save";
  if (isSubmitting) {
    buttonText = "Saving...";
  } else if (failed) {
    buttonText = "Try again";
  }
  return (
    <ModalForm>
      <ModalBody className={styles.modalBodyContainer}>
        <div className={styles.gridContainer}>
          <section>
            <dt className={styles.detailedTitle}>Maximum number of Workflows </dt>
            <dt className={styles.detailedData}>
              {errorLoadingDefaults || !defaultQuotas ? "---" : `${defaultQuotas.maxWorkflowCount} Workflows`}{" "}
            </dt>
          </section>
          <section>
            <dt className={styles.detailedTitle}>Maximum Workflow executions </dt>
            <dt className={styles.detailedData}>
              {errorLoadingDefaults || !defaultQuotas ? "---" : `${defaultQuotas.maxWorkflowRunMonthly} per month`}
            </dt>
          </section>
          <section>
            <dt className={styles.detailedTitle}>Storage limit</dt>
            <dt className={styles.detailedData}>
              {errorLoadingDefaults || !defaultQuotas ? "---" : `${defaultQuotas.maxWorkflowStorage}GB per Workflow`}
            </dt>
          </section>
          <section>
            <dt className={styles.detailedTitle}>Maximum Workflow duration</dt>
            <dt className={styles.detailedData}>
              {errorLoadingDefaults || !defaultQuotas ? "---" : `${defaultQuotas.maxWorkflowRunDuration} minutes`}
            </dt>
          </section>
          <section>
            <dt className={styles.detailedTitle}>Maximum concurrent Workflows</dt>
            <dt className={styles.detailedData}>
              {errorLoadingDefaults || !defaultQuotas ? "---" : `${defaultQuotas.maxConcurrentRuns} Workflows`}
            </dt>
          </section>
        </div>
        {failed && (
          <InlineNotification
            lowContrast
            kind="error"
            title="Quota restore default failed!"
            subtitle="Give it another go or try again later."
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={() => closeModal()}>
          Cancel
        </Button>
        <Button disabled={isSubmitting} onClick={handleRestoreDefaultQuota}>
          {buttonText}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default RestoreDefaults;
