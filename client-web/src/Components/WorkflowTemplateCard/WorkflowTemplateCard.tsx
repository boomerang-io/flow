import React, { useEffect, useState } from "react";
//@ts-ignore
import { InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { Bee } from "@carbon/react/icons";
import { ConfirmModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFetcher, useRevalidator } from "react-router-dom";
import workflowIcons from "Assets/workflowIcons";
import { Workflow } from "Types";
import styles from "./workflowTemplateCard.module.scss";

interface WorkflowTemplateCardProps {
  workflow: Workflow;
}

// Matches only the fields this component reads off TemplateWorkflows.tsx's action result for a
// "delete" intent - see that file for the actual action, and GlobalParameters.tsx for the
// closeModalRef-style pattern this card's revalidate-on-success effect follows.
type DeleteResult = {
  ok: boolean;
  intent: "delete" | "create";
  errorMessage?: { title: string; message: string };
};

const WorkflowTemplateCard: React.FC<WorkflowTemplateCardProps> = ({ workflow }) => {
  const fetcher = useFetcher<DeleteResult>();
  const revalidator = useRevalidator();
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);
  const isDeleting = fetcher.state !== "idle";

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "delete") {
      return;
    }
    if (fetcher.data.ok) {
      notify(
        <ToastNotification
          kind="success"
          title={`Delete Workflow Template`}
          subtitle={`Workflow Template successfully deleted`}
        />,
      );
      revalidator.revalidate();
    } else {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to delete Workflow Template failed`}
        />,
      );
    }
  }, [fetcher.state, fetcher.data]);

  const handleDeleteWorkflow = () => {
    fetcher.submit({ intent: "delete", name: workflow.name }, { method: "post" });
  };

  let menuOptions = [
    {
      hasDivider: true,
      itemText: "Delete",
      isDelete: true,
      onClick: () => setIsDeleteModalOpen(true),
    },
  ];

  const { name, Icon = Bee } = workflowIcons.find((icon) => icon.name === workflow.icon) ?? {};

  let loadingText = "";

  if (isDeleting) {
    loadingText = "Deleting...";
  }

  return (
    <div className={styles.container}>
      <section className={styles.details}>
        <div className={styles.iconContainer}>
          <Icon className={styles.icon} aria-label={`${name}`} />
        </div>
        <div className={styles.descriptionContainer}>
          <h1 title={workflow.name} className={styles.name} data-testid="workflow-card-title">
            {workflow.name}
          </h1>
          <p title={workflow.description} className={styles.description}>
            {workflow.description}
          </p>
        </div>
      </section>
      {isDeleting ?? (
        <InlineLoading
          description={loadingText}
          style={{ position: "absolute", left: "0.5rem", top: "0", width: "fit-content" }}
        />
      )}
      <div style={{ position: "absolute", right: "0" }}>
        <OverflowMenu flipped ariaLabel="Overflow card menu" iconDescription="Overflow menu icon" size="sm">
          {menuOptions.map(({ onClick, itemText, ...rest }, index) => (
            <OverflowMenuItem
              onClick={onClick}
              itemText={itemText}
              key={`${itemText}-${index}`}
              disabled={isDeleting}
              {...rest}
            />
          ))}
        </OverflowMenu>
      </div>
      {isDeleteModalOpen && (
        <ConfirmModal
          affirmativeAction={handleDeleteWorkflow}
          affirmativeButtonProps={{ kind: "danger" }}
          affirmativeText="Delete"
          isOpen={isDeleteModalOpen}
          negativeAction={() => {
            setIsDeleteModalOpen(false);
          }}
          negativeText="Cancel"
          onCloseModal={() => {
            setIsDeleteModalOpen(false);
          }}
          title={`Delete Workflow Template`}
        >
          {`Are you sure you want to delete this Workflow Template? There's no going back from this decision.`}
        </ConfirmModal>
      )}
    </div>
  );
};

export default WorkflowTemplateCard;
