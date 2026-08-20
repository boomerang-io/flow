import React, { useEffect, useRef } from "react";
import { Add } from "@carbon/react/icons";
import { ComposedModal, notify, ToastNotification, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { useFetcher, useNavigate, useRevalidator } from "react-router-dom";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { FeatureFlag } from "Config/appConfig";
import { FlowWorkspace, ModalTriggerProps, CreateWorkflowSummary, Workflow, WorkflowViewType } from "Types";
import CreateWorkflowContainer from "./CreateWorkflowContainer";
import styles from "./createWorkflow.module.scss";

interface CreateWorkflowProps {
  workspace?: FlowWorkspace;
  hasReachedWorkflowLimit: boolean;
  workflows: Array<Workflow>;
  viewType: WorkflowViewType;
}

// Matches only the fields this component reads off the Workflows route's action result for
// "create"/"import" intents - see Features/Workflows/Workflows.tsx for the actual action. Renders
// as a descendant of that route's element with no nested <Route> of its own, so `useFetcher()`
// resolves against it - see GlobalParameters.tsx for the closeModalRef-style pattern the import
// flow below follows, and WorkflowTemplateCard.tsx/CreateWorkflowTemplate.tsx for the sibling
// conversion (Workflow Templates) this mirrors.
type ActionResult =
  | { ok: true; intent: "create" | "import"; workflow: Workflow }
  | { ok: false; intent: "create" | "import"; errorMessage: { title: string; message: string } };

const CreateWorkflow: React.FC<CreateWorkflowProps> = ({ workspace, hasReachedWorkflowLimit, workflows, viewType }) => {
  const fetcher = useFetcher<ActionResult>();
  const revalidator = useRevalidator();
  const navigate = useNavigate();
  // useFeature returns boolean | FeatureGroup; every consumer here treats it as a plain flag.
  const workspaceQuotasEnabled = Boolean(useFeature(FeatureFlag.WorkspaceQuotasEnabled));
  // handleImportWorkflow hands this component a `closeModal` at submit time; the fetcher settles
  // asynchronously (fetcher.state -> "idle"), so the callback is stashed here and invoked from the
  // effect below only once the import actually succeeds - the modal stays open (with the inline
  // importError) on failure, matching the previous mutateAsync/try-catch behaviour. handleCreateWorkflow
  // doesn't need this: on success it navigates away immediately, same as before.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    const data = fetcher.data;

    if (data.ok) {
      navigate(appLink.editorCanvas({ workspace: workspace?.name!, workflow: data.workflow.name }));
      notify(
        <ToastNotification kind="success" title={`${data.intent === "create" ? "Create" : "Import"} ${viewType}`} subtitle={`${viewType} successfully ${data.intent === "create" ? "created" : "imported"}`} />,
      );
      if (viewType === WorkflowView.Template) {
        revalidator.revalidate();
      }
      if (data.intent === "import") {
        closeModalRef.current?.();
        closeModalRef.current = null;
      }
      return;
    }

    // create failures are silent here (the same no-op the previous catch had) - CreateWorkflowContent
    // surfaces them inline via `createError`. Import failures do get a toast, matching before.
    if (data.intent === "import") {
      notify(<ToastNotification kind="error" title={data.errorMessage.title} subtitle={data.errorMessage.message} />);
    }
  }, [fetcher.state, fetcher.data]);

  const handleCreateWorkflow = async (workflowSummary: CreateWorkflowSummary) => {
    fetcher.submit(
      { intent: "create", viewType, workflow: JSON.stringify(workflowSummary) },
      { method: "post" },
    );
  };

  const handleImportWorkflow = async (workflow: Workflow, closeModal: () => void) => {
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: "import", viewType, workflow: JSON.stringify(workflow) }, { method: "post" });
  };

  const isLoading = fetcher.state !== "idle";
  // Each content pane renders its own inline failure notification, so the two failures are kept
  // apart by intent - an import failure must not surface as a create failure, or vice versa.
  const createError = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent === "create");
  const importError = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent === "import");

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      modalTrigger={({ openModal }: ModalTriggerProps) =>
        workspaceQuotasEnabled && hasReachedWorkflowLimit ? (
          <TooltipHover
            direction="top"
            tooltipText={
              "This workspace has reached the maximum number of Workflows allowed. Contact your administrator or workspace owner to increase the quota, or delete a Workflow to create a new one."
            }
          >
            <div className={styles.disabledCreate} data-testid="workflows-create-workflow-button">
              <Add className={styles.addIcon} />
              <p className={styles.text}>{`Create a new ${viewType}`}</p>
            </div>
          </TooltipHover>
        ) : (
          <button className={styles.container} onClick={openModal} data-testid="workflows-create-workflow-button">
            <Add className={styles.addIcon} />
            <p className={styles.text}>{`Create a new ${viewType}`}</p>
          </button>
        )
      }
      confirmModalProps={{
        title: "Close this?",
        children: "Your request will not be saved",
      }}
      modalHeaderProps={{
        title: `Create a new ${viewType}`,
        subtitle: "Get started with these basics, then proceed to designing it out.",
      }}
    >
      {({ closeModal }) => (
        <CreateWorkflowContainer
          closeModal={closeModal}
          createError={createError}
          createWorkflow={handleCreateWorkflow}
          importError={importError}
          importWorkflow={handleImportWorkflow}
          isLoading={isLoading}
          workspace={workspace}
          type={viewType}
          workflows={workflows}
          workspaceQuotasEnabled={workspaceQuotasEnabled}
        />
      )}
    </ComposedModal>
  );
};

export default CreateWorkflow;
