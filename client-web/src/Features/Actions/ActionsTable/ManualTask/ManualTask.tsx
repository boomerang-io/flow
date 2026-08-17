import {
  ComposedModal,
  Loading,
  ModalForm,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import React from "react";
import ReactMarkdown from "react-markdown";
import { useQueryClient, useMutation } from "react-query";
import { useTeamContext } from "Hooks";
import "Styles/markdown.css";
import EmptyGraphic from "Components/EmptyState/EmptyGraphic";
import styles from "./ManualTask.module.scss";
import { resolver } from "Config/servicesConfig";
import { Action, ApprovalStatus, ModalTriggerProps } from "Types";

type ManualTaskProps = {
  action: Action;
  handleCloseModal?: () => void;
  modalTrigger: (args: ModalTriggerProps) => React.ReactNode;
  queryToRefetch: string;
};

function ManualTask({ action, handleCloseModal, modalTrigger, queryToRefetch }: ManualTaskProps) {
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
      {(props) => <Form action={action} queryToRefetch={queryToRefetch} {...props} />}
    </ComposedModal>
  );
}

type FormProps = {
  action: Action;
  closeModal: () => void;
  queryToRefetch: string;
};

function Form({ action, closeModal, queryToRefetch }: FormProps) {
  const queryClient = useQueryClient();
  const { team } = useTeamContext();
  const { id, instructions, status } = action ?? {};

  const {
    mutateAsync: approvalMutator,
    isLoading: approvalsIsLoading,
    error: approvalsError,
  } = useMutation(resolver.putAction, {
    onSuccess: () => {
      queryClient.invalidateQueries(queryToRefetch);
    },
  });

  const handleSubmit = async (isApproved: boolean) => {
    const body = [
      {
        id,
        approved: isApproved,
        comments: "",
      },
    ];
    try {
      await approvalMutator({ team: team?.name, body });
      notify(
        <ToastNotification
          kind="success"
          title="Manual Task"
          subtitle="Successfully submitted manual task completion request"
        />,
      );
      closeModal();
    } catch (err) {
      // noop
    }
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
