import React, { useEffect } from "react";
import { InlineLoading } from "@carbon/react";
import { Bee } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import { useFetcher, useNavigate } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { FlowWorkspaceSummary, ModalTriggerProps, WorkflowTemplate } from "Types";
import { isActionError } from "Utils/actionResult";
import CreateWorkflowContent from "./CreateWorkflowContent";
import styles from "./workflowTemplateHomeCard.module.scss";

interface WorkflowTemplateCardProps {
  template: WorkflowTemplate;
  workspaces: Array<FlowWorkspaceSummary>;
}

// Submits to Home's `action` (Features/Home/Home.tsx, intent "create-workflow-from-template") -
// this card is only ever rendered inside the Home route, with no route boundary in between, so a
// plain useFetcher() submission with no explicit `action` target lands there by default.
type CreateWorkflowActionResult =
  | { intent: "create-workflow-from-template"; workspace: string; workflow?: { name: string } }
  | { intent: "create-workflow-from-template"; workspace: string; error: { title: string; message: string } };

/** One row in Home's "Start a workflow" list; the whole row opens the create-from-template modal. */
const WorkflowTemplateCard: React.FC<WorkflowTemplateCardProps> = ({ template, workspaces }) => {
  const navigate = useNavigate();
  const fetcher = useFetcher<CreateWorkflowActionResult>();

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    // A failed create is silently swallowed here, matching the previous mutateAsync/catch
    // behaviour - CreateWorkflowContent surfaces it inline via createError instead of a toast.
    if (!isActionError(fetcher.data) && fetcher.data.workflow) {
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
  const createTemplateWorkflowError = Boolean(fetcher.data && isActionError(fetcher.data));
  const { name: iconName, Icon = Bee } = workflowIcons.find((icon) => icon.name === template.icon) ?? {};
  const title = template.displayName || template.name;

  return (
    <ComposedModal
      modalHeaderProps={{
        title: "Create Workflow from Template",
        subtitle: "Get started by leveraging this template",
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <button type="button" className={styles.row} onClick={openModal} disabled={isLoading} data-testid="template-row">
          <span className={styles.icon} aria-hidden="true">
            <Icon aria-label={iconName ?? ""} />
          </span>
          <span className={styles.body}>
            <span className={styles.name} title={title} data-testid="workflow-card-title">
              {title}
            </span>
            <span className={styles.description} title={template.description}>
              {template.description}
            </span>
          </span>
          {isLoading ? <InlineLoading description="Creating" className={styles.loading} /> : null}
        </button>
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
  );
};

export default WorkflowTemplateCard;
