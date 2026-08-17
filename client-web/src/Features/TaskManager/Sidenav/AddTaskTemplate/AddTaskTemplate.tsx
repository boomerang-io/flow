//@ts-nocheck
import React from "react";
import { Button } from "@carbon/react";
import { Add } from "@carbon/react/icons";
import { notify, ToastNotification, ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { useMutation, useQueryClient } from "react-query";
import { useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { resolver } from "Config/servicesConfig";
import AddTaskTemplateForm from "./AddTaskTemplateForm";
import styles from "./addTaskTemplate.module.scss";

interface AddTaskTemplateProps {
  taskNames: Array<string>;
  history: History;
  getTaskTemplatesUrl: string;
}

function AddTaskTemplate({ taskNames, history, getTaskTemplatesUrl }: AddTaskTemplateProps) {
  const params = useParams();
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isSubmitError, setIsSubmitError] = React.useState(false);

  const queryClient = useQueryClient();
  const createTaskTemplateMutation = useMutation(resolver.putApplyTaskTemplate);
  const createTaskTemplateYAMLMutation = useMutation(resolver.putApplyTaskTemplateYaml);
  const createTeamTaskTemplateMutation = useMutation(resolver.putApplyTeamTaskTemplate);
  const createTeamTaskTemplateYAMLMutation = useMutation(resolver.putApplyTeamTaskTemplateYaml);

  const handleAddTaskTemplate = async ({ name, replace, body, closeModal }) => {
    setIsSubmitting(true);
    try {
      let response;
      if (params.team) {
        response = await createTeamTaskTemplateMutation.mutateAsync({ team: params.team, name: name, replace, body });
      } else {
        response = await createTaskTemplateMutation.mutateAsync({ name: name, replace, body });
      }
      await queryClient.invalidateQueries(getTaskTemplatesUrl);
      notify(
        <ToastNotification
          kind="success"
          subtitle="Successfully created task template"
          title={`Task Template ${response.data.displayName} created`}
          data-testid="create-task-template-notification"
        />,
      );
      history.push(
        params.team
          ? appLink.manageTasksEdit({
              team: params.team,
              name: name,
              version: "1",
            })
          : appLink.adminTasksDetail({
              name: name,
              version: "1",
            }),
      );
      setIsSubmitting(false);
      closeModal();
    } catch (err) {
      setIsSubmitError(true);
    }
  };

  const handleImportTaskTemplate = async ({ type, name, replace, body, closeModal }) => {
    setIsSubmitting(true);
    let response;
    try {
      if (type === "application/json") {
        if (params.team) {
          response = await createTeamTaskTemplateMutation.mutateAsync({ team: params.team, name: name, replace, body });
        } else {
          response = await createTaskTemplateMutation.mutateAsync({ name: name, replace, body });
        }
      } else {
        if (params.team) {
          response = await createTeamTaskTemplateYAMLMutation.mutateAsync({
            team: params.team,
            name: name,
            replace,
            body,
          });
        } else {
          response = await createTaskTemplateYAMLMutation.mutateAsync({ name: name, replace, body });
        }
      }
      await queryClient.invalidateQueries(getTaskTemplatesUrl);
      notify(
        <ToastNotification
          kind="success"
          subtitle="Successfully imported task template"
          title={`Task Template ${response.data.displayName} imported`}
          data-testid="import-task-template-notification"
        />,
      );
      history.push(
        params.team
          ? appLink.manageTasksEdit({
              team: params.team,
              name: task.name,
              version: task.version.toString(),
            })
          : appLink.adminTasksDetail({
              name: task.name,
              version: task.version.toString(),
            }),
      );
      closeModal();
      setIsSubmitting(false);
    } catch (err) {
      setIsSubmitError(true);
    }
  };

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      confirmModalProps={{
        title: "Close this?",
        children: "Your request will not be saved",
      }}
      modalTrigger={({ openModal }) => (
        <Button iconDescription="Add task template" onClick={openModal} size="sm" kind="ghost" renderIcon={Add}>
          Add a new task
        </Button>
      )}
      modalHeaderProps={{
        title: "Add a new task",
        subtitle: "Get started from scratch with these basics, or import a file to auto-populate these fields.",
      }}
    >
      {({ closeModal }) => (
        <AddTaskTemplateForm
          handleAddTaskTemplate={handleAddTaskTemplate}
          handleImportTaskTemplate={handleImportTaskTemplate}
          isSubmitting={isSubmitting}
          createError={isSubmitError}
          taskNames={taskNames}
          closeModal={closeModal}
        />
      )}
    </ComposedModal>
  );
}

export default AddTaskTemplate;
