import { Add } from "@carbon/react/icons";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { ModalTriggerProps } from "Types";
import WorkspaceCreateContent from "./WorkspaceCreateContent";
import styles from "./workspaceCardCreate.module.scss";

interface WorkspaceCardProps {
  createWorkspace: (values: { name: string | undefined }, success_fn: () => void) => void;
  isError: boolean;
  isLoading: boolean;
}

function WorkspaceCard(props: WorkspaceCardProps) {
  return (
    <div className={styles.container}>
      <ComposedModal
        composedModalProps={{ shouldCloseOnOverlayClick: true }}
        modalHeaderProps={{
          title: "Create Workspace",
          subtitle: `Set up your workspace. The display name will be used to create a unique identifier for your workspace. Display names can be adjusted post workspace creation.`,
        }}
        modalTrigger={({ openModal }: ModalTriggerProps) => (
          <button className={styles.content} onClick={openModal} data-testid="workflows-create-workflow-button">
            <Add className={styles.addIcon} />
            <p className={styles.text}>{`Create a new Workspace`}</p>
          </button>
        )}
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
    </div>
  );
}

export default WorkspaceCard;
