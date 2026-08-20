import React, { useEffect, useRef } from "react";
import { Add } from "@carbon/react/icons";
import { ComposedModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFetcher, useRevalidator } from "react-router-dom";
import { ModalTriggerProps, Workflow } from "Types";
import ImportWorkflowContainer from "./ImportWorkflowContainer";
import styles from "./createWorkflowTemplate.module.scss";

interface CreateWorkflowProps {
  workflows: Array<Workflow>;
}

// Matches only the fields this component reads off TemplateWorkflows.tsx's action result for a
// "create" intent - see that file for the actual action, and GlobalParameters.tsx for the
// closeModalRef-style pattern this modal's submit-then-close-on-success flow follows.
type CreateResult = {
  ok: boolean;
  intent: "create" | "delete";
  errorMessage?: { title: string; message: string };
};

const CreateWorkflow: React.FC<CreateWorkflowProps> = ({ workflows }) => {
  const fetcher = useFetcher<CreateResult>();
  const revalidator = useRevalidator();
  // handleImportWorkflow hands this component a `closeModal` at submit time; the fetcher settles
  // asynchronously (fetcher.state -> "idle"), so the callback is stashed here and invoked from
  // the effect below only on success - the modal stays open (with the inline importError) on
  // failure, matching the previous mutateAsync/try-catch behaviour.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "create") {
      return;
    }
    if (fetcher.data.ok) {
      notify(
        <ToastNotification kind="success" title={`Import Workflow Template`} subtitle={`Template successfully imported`} />,
      );
      revalidator.revalidate();
      closeModalRef.current?.();
      closeModalRef.current = null;
    } else {
      const errorMessage = fetcher.data.errorMessage;
      notify(
        <ToastNotification
          kind="error"
          title={errorMessage?.title ?? "Something's Wrong"}
          subtitle={errorMessage?.message}
        />,
      );
    }
  }, [fetcher.state, fetcher.data]);

  const handleImportWorkflow = async (workflow: Workflow, closeModal: () => void) => {
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: "create", workflow: JSON.stringify(workflow) }, { method: "post" });
  };

  const isLoading = fetcher.state !== "idle";
  const importError = Boolean(fetcher.data && fetcher.data.intent === "create" && !fetcher.data.ok);

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
        subtitle: "Craft the Workflow in your workspace and import the file.",
      }}
    >
      {({ closeModal }) => (
        <ImportWorkflowContainer
          closeModal={closeModal}
          importError={importError}
          importWorkflow={handleImportWorkflow}
          isLoading={isLoading}
          workflows={workflows}
        />
      )}
    </ComposedModal>
  );
};

export default CreateWorkflow;
