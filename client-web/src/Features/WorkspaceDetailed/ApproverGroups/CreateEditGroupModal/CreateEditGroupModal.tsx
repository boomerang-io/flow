import React from "react";
import { Button } from "@carbon/react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import CreateEditGroupModalContent from "./CreateEditGroupModalContent";
import { FlowWorkspace, ApproverGroup } from "Types";
import { Add, Edit } from "@carbon/react/icons";
import styles from "./createEditGroupModal.module.scss";

type CreateEditGroupModalProps = {
  approverGroup?: ApproverGroup;
  approverGroups: ApproverGroup[];
  isEdit?: boolean;
  workspace?: FlowWorkspace | null;
  workspaceDetailsUrl: string;
};

function CreateEditGroupModal({
  approverGroup,
  approverGroups,
  isEdit,
  workspace,
  workspaceDetailsUrl,
}: CreateEditGroupModalProps) {
  /**
   * arrays of values for making the key unique
   * filter out own value if editing a approverGroup, pass through all if creating
   */
  let approverGroupNames: string[] = [];
  if (Array.isArray(approverGroups)) {
    approverGroupNames = approverGroups.map((configurationObj) => configurationObj.name.toLowerCase());
    if (isEdit && approverGroup) {
      approverGroupNames = approverGroupNames.filter(
        (approverGroupItem) => approverGroupItem !== approverGroup.name.toLowerCase()
      );
    }
  }

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer, shouldCloseOnOverlayClick: false }}
      modalTrigger={({ openModal }: any) =>
        !isEdit ? (
          <Button renderIcon={Add} size="md" kind="primary" onClick={openModal}>
            Create new group
          </Button>
        ) : (
          <Button
            className={styles.editButton}
            onClick={openModal}
            kind="ghost"
            renderIcon={Edit}
            size="sm"
            iconDescription="Edit approver group"
            data-testid="edit-approver-group"
          />
        )
      }
      modalHeaderProps={{
        title: isEdit && approverGroup ? `Edit ${approverGroup.name}` : "Create new group",
        subtitle:
          "Choose a group name, then add users from a selected Workspace. Groups can only be formed with users from the same Workspace. Members will receive an email notification about their new permissions.",
      }}
    >
      {({ closeModal }) => (
        <CreateEditGroupModalContent
          isEdit={isEdit}
          approverGroup={approverGroup}
          approverGroups={approverGroupNames}
          workspace={workspace}
          closeModal={closeModal}
          workspaceDetailsUrl={workspaceDetailsUrl}
        />
      )}
    </ComposedModal>
  );
}

export default CreateEditGroupModal;
