import React from "react";
import { notify, ToastNotification, ModalFlow } from "@boomerang-io/carbon-addons-boomerang-react";
import queryString from "query-string";
import { useMutation, useQueryClient } from "react-query";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { Workflow } from "Types";
import ImportWorkflowContent from "./ImportWorkflowContent";
import styles from "./updateWorkflow.module.scss";

interface UpdateWorkflowProps {
  teamName: string | null;
  workflowRef: string;
  getWorkflowsUrl: string;
  onCloseModal: () => void;
  type: string;
}

const UpdateWorkflow: React.FC<UpdateWorkflowProps> = ({
  teamName,
  workflowRef,
  getWorkflowsUrl,
  onCloseModal,
  type,
}) => {
  const queryClient = useQueryClient();

  //TODO - update the query and mutator as post endpoint different
  const { mutateAsync: importWorkflowMutator, isLoading: isPosting } = useMutation(resolver.putApplyWorkflow);
  const handleImportWorkflow = async (data: Workflow, closeModal: () => void) => {
    try {
      await importWorkflowMutator({ team: teamName, workflow: workflowRef, body: data });
      queryClient.invalidateQueries(getWorkflowsUrl);
      notify(<ToastNotification kind="success" title={`Update ${type}`} subtitle={`${type} successfully updated`} />);
      closeModal();
    } catch {
      // no-op
    }
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
