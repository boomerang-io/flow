import React from "react";
import { InlineLoading, Button } from "@carbon/react";
import { TrashCan } from "@carbon/react/icons";
import { MemberRole } from "Types";
import styles from "./memberCard.module.scss";

interface MemberCardProps {
  email: string;
  role: MemberRole;
  handleRemove: Function;
  isRemoving: boolean;
}

function MemberCard({ email, role, handleRemove, isRemoving }: MemberCardProps) {
  return (
    <div className={styles.container}>
      <section className={styles.details}>
        <div className={styles.descriptionContainer}>
          <h1 title={email} className={styles.name} data-testid="workflow-card-title">
            {email}
          </h1>
          <p title={role} className={styles.description}>
            {role}
          </p>
        </div>
      </section>
      {isRemoving ? (
        <InlineLoading
          description="Removing.."
          style={{ position: "absolute", right: "0.5rem", top: "0", width: "fit-content" }}
        />
      ) : (
        <Button
          className={styles.actionButton}
          ariaLabel="Remove member button"
          iconDescription="Remove member icon"
          kind="danger--ghost"
          onClick={handleRemove}
          renderIcon={TrashCan}
          size="md"
        />
      )}
    </div>
  );
}

export default MemberCard;
