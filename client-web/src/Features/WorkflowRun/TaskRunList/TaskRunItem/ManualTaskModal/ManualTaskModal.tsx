import { notify, Loading, ModalForm, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import ReactMarkdown from "react-markdown";
import { useTeamContext } from "Hooks";
import { useQueryClient, useMutation } from "react-query";
import "Styles/markdown.css";
import { serviceUrl, resolver } from "Config/servicesConfig";

type Props = {
  actionId?: string;
  closeModal: () => void;
  instructions?: string;
  workflowRunId: string;
};

// TODO: update to load info about the approval
// Need to get the approval and the instructions
function TaskApprovalModal({ actionId, closeModal, instructions, workflowRunId }: Props) {
  const queryClient = useQueryClient();
  const { team } = useTeamContext();

  const {
    mutateAsync: approvalMutator,
    isLoading: approvalsIsLoading,
    error: approvalsError,
  } = useMutation(resolver.putAction, {
    onSuccess: () => {
      queryClient.invalidateQueries(serviceUrl.team.workflowrun.getWorkflowRun({ team: team.name, id: workflowRunId }));
    },
  });

  const handleSubmit = async (approvalValue: boolean) => {
    const body = [
      {
        id: actionId,
        approved: approvalValue,
        comments: "",
      },
    ];
    try {
      await approvalMutator({ team: team.name, body });
      notify(
        <ToastNotification
          kind="success"
          title="Manual Task"
          subtitle={"Successfully submitted manual task completion request"}
        />,
      );
      closeModal();
    } catch (err) {
      // noop
    }
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
