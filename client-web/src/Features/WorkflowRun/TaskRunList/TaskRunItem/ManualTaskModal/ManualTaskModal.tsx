import React from "react";
import { notify, Loading, ModalForm, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import ReactMarkdown from "react-markdown";
import { useFetcher } from "react-router-dom";
import "Styles/markdown.css";
import type { ActionResult } from "Features/WorkflowRun/WorkflowRun";
import { isActionError } from "Utils/actionResult";

type Props = {
  actionId?: string;
  closeModal: () => void;
  instructions?: string;
};

function TaskApprovalModal({ actionId, closeModal, instructions }: Props) {
  // Submits to the run route's `action` (WorkflowRun.tsx); its completion revalidates the route
  // loader, replacing the invalidateQueries(getWorkflowRun) this used to do onSuccess.
  const fetcher = useFetcher<ActionResult>();
  const approvalsIsLoading = fetcher.state !== "idle";
  const approvalsError = Boolean(fetcher.data && isActionError(fetcher.data));

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || isActionError(fetcher.data)) {
      return;
    }
    notify(
      <ToastNotification
        kind="success"
        title="Manual Task"
        subtitle={"Successfully submitted manual task completion request"}
      />,
    );
    closeModal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = (approvalValue: boolean) => {
    fetcher.submit(
      { intent: "action", actionId: actionId ?? "", approved: String(approvalValue), comments: "" },
      { method: "post" },
    );
  };

  return (
    <ModalForm>
      {approvalsIsLoading && <Loading />}
      <ModalBody>
        <ReactMarkdown className="markdown-body" children={instructions ?? ""} />
        {Boolean(approvalsError) && (
          <InlineNotification
            style={{ marginBottom: "0.5rem" }}
            lowContrast
            kind="error"
            title={"Manual Task Failed"}
            subtitle={"Something's Wrong"}
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Cancel
        </Button>
        <Button disabled={approvalsIsLoading} type="submit" kind="danger" onClick={() => handleSubmit(false)}>
          Complete Unsuccessfully
        </Button>
        <Button disabled={approvalsIsLoading} type="submit" onClick={() => handleSubmit(true)}>
          Complete Successfully
        </Button>
      </ModalFooter>
    </ModalForm>
  );
}

export default TaskApprovalModal;
