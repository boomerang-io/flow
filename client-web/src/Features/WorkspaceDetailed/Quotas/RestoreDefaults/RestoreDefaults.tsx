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
import { isActionError } from "Utils/actionResult";
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

// Every quota a restore resets, titled and unitised as its card on the Quotas screen.
function defaultQuotaRows(quotas?: QuotasLoaderData["defaultQuotas"]) {
  const show = (text: (q: NonNullable<QuotasLoaderData["defaultQuotas"]>) => string) => (quotas ? text(quotas) : "---");
  return [
    { title: "Number of Workflows", value: show((q) => `${q.maxWorkflowCount} Workflows`) },
    { title: "Number of Executions", value: show((q) => `${q.maxWorkflowRunMonthly} per month`) },
    { title: "Run Duration", value: show((q) => `${q.maxWorkflowRunDuration} minutes`) },
    { title: "Concurrent Runs (executions)", value: show((q) => `${q.maxConcurrentRuns} Workflows`) },
    { title: "Workspace Capacity - Per Workflow", value: show((q) => `${q.maxWorkflowStorage}GB per Workflow`) },
    { title: "Workspace Capacity - Per Run", value: show((q) => `${q.maxWorkflowRunStorage}GB per WorkflowRun`) },
    { title: "Artifact Storage", value: show((q) => `${q.maxArtifactStorage}GB`) },
    { title: "Artifact Retention", value: show((q) => `${q.artifactRetentionDays} days`) },
  ];
}

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
  const failed = Boolean(fetcher.data && isActionError(fetcher.data) && fetcher.data.intent === "restore");

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "restore") {
      return;
    }
    if (!isActionError(fetcher.data)) {
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
          {defaultQuotaRows(errorLoadingDefaults ? undefined : defaultQuotas).map(({ title, value }) => (
            <section key={title}>
              <dt className={styles.detailedTitle}>{title}</dt>
              <dt className={styles.detailedData}>{value}</dt>
            </section>
          ))}
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
