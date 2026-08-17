import React from "react";
import { Add } from "@carbon/react/icons";
import { ComposedModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useMutation, useQueryClient } from "react-query";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { ModalTriggerProps, Workflow } from "Types";
import ImportWorkflowContainer from "./ImportWorkflowContainer";
import styles from "./createWorkflowTemplate.module.scss";

interface CreateWorkflowProps {
  workflows: Array<Workflow>;
}

const CreateWorkflow: React.FC<CreateWorkflowProps> = ({ workflows }) => {
  const queryClient = useQueryClient();

  const createTemplateMutator = useMutation(resolver.postCreateTemplate);

  const handleImportWorkflow = async (workflow: Workflow, closeModal: () => void) => {
    try {
      await createTemplateMutator.mutateAsync({
        body: workflow,
      });
      notify(
        <ToastNotification
          kind="success"
          title={`Import Workflow Template`}
          subtitle={`Template successfully imported`}
        />,
      );
      queryClient.invalidateQueries(serviceUrl.template.getWorkflowTemplates());
      closeModal();
    } catch (err) {
      const errorMessages = formatErrorMessage({
        error: err,
        defaultMessage: `Import Template Failed`,
      });
      notify(<ToastNotification kind="error" title={errorMessages.title} subtitle={errorMessages.message} />);
    }
  };

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <button className={styles.container} onClick={openModal} data-testid="workflows-create-workflow-button">
          <Add className={styles.addIcon} />
          <p className={styles.text}>{`Import new Workflow Template`}</p>
        </button>
      )}
      confirmModalProps={{
        title: "Close this?",
        children: "Your request will not be saved",
      }}
      modalHeaderProps={{
        title: `Import a new Workflow Template`,
        subtitle: "Craft the Workflow in your team and import the file.",
      }}
    >
      {({ closeModal }) => (
        <ImportWorkflowContainer
          closeModal={closeModal}
          importError={createTemplateMutator.error}
          importWorkflow={handleImportWorkflow}
          workflows={workflows}
        />
      )}
    </ComposedModal>
  );
};

export default CreateWorkflow;
