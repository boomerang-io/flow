import {
  ComposedModal,
  Loading,
  ModalForm,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { Reset } from "@carbon/react/icons";
import React from "react";
import { useMutation, useQuery } from "react-query";
import styles from "./RestoreDefaults.module.scss";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { ModalTriggerProps, FlowTeam } from "Types";

interface RestoreDefaultsProps {
  team: FlowTeam;
  disabled: boolean;
}

const RestoreDefaults: React.FC<RestoreDefaultsProps> = ({ team, disabled }) => {
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
      {({ closeModal }) => <RestoreModalContent closeModal={closeModal} teamName={team.name} />}
    </ComposedModal>
  );
};

interface restoreDefaultProps {
  closeModal: Function;
  teamName: string;
}

const RestoreModalContent: React.FC<restoreDefaultProps> = ({ closeModal, teamName }) => {
  const defaultQuotasQuery = useQuery({
    queryKey: serviceUrl.getTeamQuotaDefaults(),
    queryFn: resolver.query(serviceUrl.getTeamQuotaDefaults()),
  });

  const resetQuotasMutator = useMutation(resolver.deleteTeamQuotas);

  const handleRestoreDefaultQuota = async () => {
    try {
      await resetQuotasMutator.mutateAsync({ team: teamName });
      closeModal();
      notify(
        <ToastNotification
          kind="success"
          title="Restore Default Quotas"
          subtitle="Successfully restored default quotas"
        />,
      );
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle="Failed to restore default quotas" />);
    }
  };

  let buttonText = "Save";
  if (resetQuotasMutator.isLoading) {
    buttonText = "Saving...";
  } else if (resetQuotasMutator.error) {
    buttonText = "Try again";
  }
  return (
    <ModalForm>
      <ModalBody className={styles.modalBodyContainer}>
        {defaultQuotasQuery.isLoading ? (
          <Loading />
        ) : (
          <div className={styles.gridContainer}>
            <section>
              <dt className={styles.detailedTitle}>Maximum number of Workflows </dt>
              <dt className={styles.detailedData}>
                {defaultQuotasQuery.error ? "---" : `${defaultQuotasQuery.data.maxWorkflowCount} Workflows`}{" "}
              </dt>
            </section>
            <section>
              <dt className={styles.detailedTitle}>Maximum Workflow executions </dt>
              <dt className={styles.detailedData}>
                {defaultQuotasQuery.error ? "---" : `${defaultQuotasQuery.data.maxWorkflowExecutionMonthly} per month`}
              </dt>
            </section>
            <section>
              <dt className={styles.detailedTitle}>Storage limit</dt>
              <dt className={styles.detailedData}>
                {defaultQuotasQuery.error
                  ? "---"
                  : `${defaultQuotasQuery.data.maxWorkflowExecutionMonthly}GB per Workflow`}
              </dt>
            </section>
            <section>
              <dt className={styles.detailedTitle}>Maximum Workflow duration</dt>
              <dt className={styles.detailedData}>
                {defaultQuotasQuery.error ? "---" : `${defaultQuotasQuery.data.maxWorkflowExecutionTime} minutes`}
              </dt>
            </section>
            <section>
              <dt className={styles.detailedTitle}>Maximum concurrent Workflows</dt>
              <dt className={styles.detailedData}>
                {defaultQuotasQuery.error ? "---" : `${defaultQuotasQuery.data.maxConcurrentWorkflows} Workflows`}
              </dt>
            </section>
          </div>
        )}
        {resetQuotasMutator.error && (
          <InlineNotification
            lowContrast
            kind="error"
            title="Quota restore default failed!"
            subtitle="Give it another go or try again later."
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Cancel
        </Button>
        <Button disabled={resetQuotasMutator.isLoading} onClick={handleRestoreDefaultQuota}>
          {buttonText}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default RestoreDefaults;
