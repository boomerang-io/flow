import React from "react";
import { CloseOutline } from "@carbon/react/icons";
import styles from "./MemberBar.module.scss";

interface MemberBarProps {
  addUser: Function | null;
  id: string;
  email: string;
  name: string;
  removeUser: Function | null;
}

const MemberBar: React.FC<MemberBarProps> = ({ addUser, id, email, name, removeUser }) => {
  return (
    <li key={id}>
      <button
        className={styles.container}
        onClick={addUser ? () => addUser(id) : removeUser ? () => removeUser(id) : () => {}}
        type="button"
      >
        <div className={styles.userRow}>
          <div className={styles.textContainer}>
            <p className={styles.name}>{name}</p>
            <p className={styles.email}>{email}</p>
          </div>
        </div>
        {removeUser && (
          <CloseOutline size={32} className={styles.closeIcon} alt="remove user" data-testid="remove-user-button" />
        )}
      </button>
    </li>
  );
};

export default MemberBar;
