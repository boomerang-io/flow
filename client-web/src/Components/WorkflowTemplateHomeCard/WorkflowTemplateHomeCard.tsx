import React, { useEffect, useState } from "react";
//@ts-ignore
import { Button, InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { Run, Bee, CircleFill, InformationFilled, Template, Add } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import { Link, useFetcher, useNavigate } from "react-router-dom";
import { appLink, FeatureFlag } from "Config/appConfig";
import { FlowWorkspaceSummary, ModalTriggerProps, WorkflowTemplate } from "Types";
import CreateWorkflowContent from "./CreateWorkflowContent";
import styles from "./workflowTemplateHomeCard.module.scss";

interface WorkflowTemplateCardProps {
  template: WorkflowTemplate;
  workspaces: Array<FlowWorkspaceSummary>;
}

// Submits to Home's `action` (Features/Home/Home.tsx, intent "create-workflow-from-template") -
// this card is only ever rendered inside the Home route, with no route boundary in between, so a
// plain useFetcher() submission with no explicit `action` target lands there by default.
type CreateWorkflowActionResult = {
  ok: boolean;
  intent: "create-workflow-from-template";
  workspace: string;
  workflow?: { name: string };
};

const WorkflowTemplateCard: React.FC<WorkflowTemplateCardProps> = ({ template, workspaces }) => {
  const navigate = useNavigate();
  const fetcher = useFetcher<CreateWorkflowActionResult>();

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    // A failed create is silently swallowed here, matching the previous mutateAsync/catch
    // behaviour - CreateWorkflowContent surfaces it inline via createError instead of a toast.
    if (fetcher.data.ok && fetcher.data.workflow) {
      navigate(appLink.editorCanvas({ workspace: fetcher.data.workspace, workflow: fetcher.data.workflow.name }));
      notify(
        <ToastNotification
          kind="success"
          title="Create Workflow"
          subtitle="Successfully created workflow from template"
        />,
      );
    }
  }, [fetcher.state, fetcher.data]);

  const handleCreateWorkflow = async (
    workspace: string,
    requestBody: { name: string; description: string; icon: string },
  ) => {
    const body = { ...template, ...requestBody };
    fetcher.submit({ intent: "create-workflow-from-template", workspace, body: JSON.stringify(body) }, { method: "post" });
  };
  const isLoading = fetcher.state !== "idle";
  const createTemplateWorkflowError = Boolean(fetcher.data && !fetcher.data.ok);
  const { name, Icon = Bee } = workflowIcons.find((icon) => icon.name === template.icon) ?? {};

  let loadingText = "";

  return (
    <div className={styles.container}>
      <section className={styles.details}>
        <div className={styles.iconContainer}>
          <Icon className={styles.icon} aria-label={`${name}`} />
        </div>
        <div className={styles.descriptionContainer}>
          <h1 title={template.name} className={styles.name} data-testid="workflow-card-title">
            {template.name}
          </h1>
          <p title={template.description} className={styles.description}>
            {template.description}
          </p>
        </div>
      </section>
      <section className={styles.launch}>
        <ComposedModal
          modalHeaderProps={{
            title: "Create Workflow from Template",
            subtitle: "Get started by leveraging this template",
          }}
          modalTrigger={({ openModal }: ModalTriggerProps) => (
            <Button iconDescription={`Create from Template`} renderIcon={Template} size="md" onClick={openModal}>
              Create from template
            </Button>
          )}
        >
          {({ closeModal }) => (
            <CreateWorkflowContent
              template={template}
              createWorkflow={handleCreateWorkflow}
              createError={createTemplateWorkflowError}
              isLoading={isLoading}
              workspaces={workspaces}
            />
          )}
        </ComposedModal>
      </section>
      {isLoading ? (
        <InlineLoading
          description={loadingText}
          style={{ position: "absolute", left: "0.5rem", top: "0", width: "fit-content" }}
        />
      ) : null}
    </div>
  );
};

export default WorkflowTemplateCard;
