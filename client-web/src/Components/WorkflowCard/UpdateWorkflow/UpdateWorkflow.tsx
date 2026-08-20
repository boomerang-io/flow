import React, { useEffect } from "react";
import { notify, ToastNotification, ModalFlow } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFetcher, useRevalidator } from "react-router-dom";
import { Workflow } from "Types";
import ImportWorkflowContent from "./ImportWorkflowContent";
import styles from "./updateWorkflow.module.scss";

interface UpdateWorkflowProps {
  workspaceName: string | null;
  workflowRef: string;
  onCloseModal: () => void;
  type: string;
}

// Matches only the fields this component reads off the Workflows route's action result for the
// "update" intent it submits - see Features/Workflows/Workflows.tsx for the actual action.
// Renders as a descendant of that route's element (via WorkflowCard.tsx, itself a descendant, no
// nested <Route> anywhere in between), so `useFetcher()` resolves against it.
//
// Request-shape note: the previous mutateAsync call here (`importWorkflowMutator({ workspace:
// workspaceName, body: data })`) only ever sent `workspace` and `body` - matching
// serviceUrl.workspace.workflow.putApplyWorkflow's signature (`{ workspace }` only, PUT
// /workspace/{workspace}/workflow, the workflow identified by `body.name`) exactly. There was no
// extra `workflow` field being sent that the URL builder ignored; the action below preserves the
// same shape (workspace from the route param, the full parsed file as the body).
type ActionResult = { ok: true; intent: "update" } | { ok: false; intent: "update" };

const UpdateWorkflow: React.FC<UpdateWorkflowProps> = ({ workspaceName, workflowRef, onCloseModal, type }) => {
  const fetcher = useFetcher<ActionResult>();
  const revalidator = useRevalidator();
  const isPosting = fetcher.state !== "idle";

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "update") {
      return;
    }
    if (fetcher.data.ok) {
      revalidator.revalidate();
      notify(<ToastNotification kind="success" title={`Update ${type}`} subtitle={`${type} successfully updated`} />);
      onCloseModal();
    }
    // failures are a no-op here, matching the previous catch.
  }, [fetcher.state, fetcher.data]);

  const handleImportWorkflow = async (data: Workflow) => {
    fetcher.submit({ intent: "update", workflow: JSON.stringify(data) }, { method: "post" });
  };

  return (
    <ModalFlow
      isOpen
      confirmModalProps={{
        title: "Are you sure?",
        children: "Your request will not be saved",
      }}
      composedModalProps={{
        containerClassName: styles.container,
      }}
      modalHeaderProps={{
        title: `Update ${type}`,
      }}
      onCloseModal={onCloseModal}
    >
      <ImportWorkflowContent
        confirmButtonText={isPosting ? "Updating..." : "Update"}
        handleImportWorkflow={handleImportWorkflow}
        isLoading={isPosting}
        title={`Select the ${type} JSON file you want to update the current ${type} with. The ${type} name (slug) must match.`}
        workflowRef={workflowRef}
        type={type}
      />
    </ModalFlow>
  );
};

export default UpdateWorkflow;
