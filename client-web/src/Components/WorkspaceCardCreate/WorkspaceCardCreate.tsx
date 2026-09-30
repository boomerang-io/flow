import React from "react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Add } from "@carbon/react/icons";
import { ModalTriggerProps } from "Types";
import WorkspaceCreateContent from "./WorkspaceCreateContent";
import styles from "./workspaceCardCreate.module.scss";

interface WorkspaceCardProps {
  createWorkspace: (values: { name: string | undefined }, success_fn: () => void) => void;
  isError: boolean;
  isLoading: boolean;
  /**
   * What opens the modal. The default is the dashed "Create a new Workspace" tile that sits beside
   * the workspace cards; Home also mounts the same modal behind a hero button and behind the
   * getting-started step, so the trigger is a prop.
   */
  modalTrigger?: (args: ModalTriggerProps) => React.ReactNode;
}

function WorkspaceCardCreate(props: WorkspaceCardProps) {
  const modalTrigger =
    props.modalTrigger ??
    (({ openModal }: ModalTriggerProps) => (
      <div className={styles.container}>
        <button className={styles.content} onClick={openModal} data-testid="workflows-create-workflow-button">
          <Add className={styles.addIcon} aria-hidden="true" />
          <p className={styles.text}>{`Create a new Workspace`}</p>
        </button>
      </div>
    ));

  return (
    <ComposedModal
      composedModalProps={{ shouldCloseOnOverlayClick: true }}
      modalHeaderProps={{
        title: "Create Workspace",
        subtitle: `Set up your workspace. The display name will be used to create a unique identifier for your workspace. Display names can be adjusted post workspace creation.`,
      }}
      modalTrigger={modalTrigger}
    >
      {({ closeModal }) => {
        return (
          <WorkspaceCreateContent
            closeModal={closeModal}
            createWorkspace={props.createWorkspace}
            isError={props.isError}
            isLoading={props.isLoading}
          />
        );
      }}
    </ComposedModal>
  );
}

export default WorkspaceCardCreate;
