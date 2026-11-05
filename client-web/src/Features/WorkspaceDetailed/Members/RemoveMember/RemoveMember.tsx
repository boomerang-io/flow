import React from "react";
import { ConfirmModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { TrashCan } from "@carbon/react/icons";
import { Member } from "Types";
import styles from "./RemoveMember.module.scss";

interface RemoveMemberProps {
  member: Member;
  workspaceName: string;
  // Your own row, and the last owner's, cannot be removed.
  isLocked: boolean;
  isRemoving: boolean;
  onRemove: (member: Member) => void;
}

// The submission and its toast live in ../Members: this row unmounts as soon as the list
// revalidates without it, so a result handled here would never be seen.
const RemoveMember: React.FC<RemoveMemberProps> = ({ member, workspaceName, isLocked, isRemoving, onRemove }) => {
  return (
    <ConfirmModal
      affirmativeAction={() => onRemove(member)}
      affirmativeButtonProps={{ kind: "danger", disabled: isRemoving, "data-testid": "remove-member" }}
      negativeButtonProps={{ disabled: isRemoving }}
      children={`Are you sure you want to remove ${member.name} from ${workspaceName}? The user will lose access to all workspace workflows.`}
      title={`Remove from Workspace`}
      modalTrigger={({ openModal }) => (
        <button className={styles.removeButton} disabled={isLocked || isRemoving} onClick={openModal}>
          Remove from Workspace
          <TrashCan fill={"#f94d56"} />
        </button>
      )}
    />
  );
};

export default RemoveMember;
