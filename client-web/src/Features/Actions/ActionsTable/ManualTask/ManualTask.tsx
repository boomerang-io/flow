import {
  ComposedModal,
  Loading,
  ModalForm,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import React, { useEffect, useRef } from "react";
import ReactMarkdown from "react-markdown";
import { useFetcher, useRevalidator } from "react-router-dom";
import "Styles/markdown.css";
import EmptyGraphic from "Components/EmptyState/EmptyGraphic";
import styles from "./ManualTask.module.scss";
import type { ActionResult } from "Features/Actions/Actions";
import { Action, ApprovalStatus, ModalTriggerProps } from "Types";

type ManualTaskProps = {
  action: Action;
  handleCloseModal?: () => void;
  modalTrigger: (args: ModalTriggerProps) => React.ReactNode;
};

function ManualTask({ action, handleCloseModal, modalTrigger }: ManualTaskProps) {
  return (
    <ComposedModal
      modalTrigger={modalTrigger}
      composedModalProps={{
        containerClassName: styles.actionManualTaskModalContainer,
        shouldCloseOnOverlayClick: true,
      }}
      modalHeaderProps={{ title: "Action Manual Task", subtitle: action?.taskName }}
      onCloseModal={() => {
        handleCloseModal && handleCloseModal();
      }}
    >
      {(props) => <Form action={action} {...props} />}
    </ComposedModal>
  );
}

type FormProps = {
  action: Action;
  closeModal: () => void;
};

function Form({ action, closeModal }: FormProps) {
  const revalidator = useRevalidator();
  const fetcher = useFetcher<ActionResult>();
  const { id, instructions, status } = action ?? {};
  // The fetcher settles asynchronously; the closeModal callback is invoked from the effect below
  // only on success, matching the previous mutateAsync/then-based behaviour (modal stays open with
  // the inline error banner on failure). Refresh via useRevalidator().revalidate() rather than
  // react-query's queryClient.invalidateQueries - once the read is loader-driven, invalidateQueries
  // is an inert no-op (see CLAUDE.md).
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.ok) {
      revalidator.revalidate();
      notify(
        <ToastNotification
          kind="success"
          title="Manual Task"
          subtitle="Successfully submitted manual task completion request"
        />,
      );
      closeModalRef.current?.();
      closeModalRef.current = null;
    }
    // failures leave the modal open - the InlineNotification below (driven off
    // approvalsError/fetcher.data) surfaces the error inline, matching the previous behaviour.
  }, [fetcher.state, fetcher.data]);

  const approvalsIsLoading = fetcher.state !== "idle";
  const approvalsError = Boolean(fetcher.data && !fetcher.data.ok);

  const handleSubmit = (isApproved: boolean) => {
    const body = [
      {
        id,
        approved: isApproved,
        comments: "",
      },
    ];
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: "putAction", body: JSON.stringify(body) }, { method: "post" });
  };

  if (status !== ApprovalStatus.Submitted) {
    return (
      <ModalForm>
        <ModalBody>
          <p>
            Manual task was previously <strong>{status}</strong>. There's nothing to do here.
          </p>
          <EmptyGraphic style={{ width: "28rem" }} />
        </ModalBody>
        <ModalFooter>
          <Button kind="secondary" type="button" onClick={closeModal}>
            Close
          </Button>
        </ModalFooter>
      </ModalForm>
    );
  }

  return (
    <ModalForm>
      {approvalsIsLoading && <Loading />}
      <ModalBody>
        {instructions ? <ReactMarkdown className="markdown-body" children={instructions} /> : <p>No instructions.</p>}
        {Boolean(approvalsError) && (
          <InlineNotification
            lowContrast
            kind="error"
            title="Something's Wrong"
            subtitle="Failed to action manual task"
            style={{ marginBottom: "0.5rem" }}
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

export default ManualTask;
