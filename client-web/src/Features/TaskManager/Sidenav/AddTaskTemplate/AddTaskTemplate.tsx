//@ts-nocheck
import React from "react";
import { Button } from "@carbon/react";
import { Add } from "@carbon/react/icons";
import { notify, ToastNotification, ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { NavigateFunction, useFetcher, useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { Task } from "Types";
import AddTaskTemplateForm from "./AddTaskTemplateForm";
import styles from "./addTaskTemplate.module.scss";

interface AddTaskTemplateProps {
  taskNames: Array<string>;
  navigate: NavigateFunction;
  getTaskTemplatesUrl: string;
}

function AddTaskTemplate({ taskNames, navigate }: AddTaskTemplateProps) {
  const params = useParams();
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isSubmitError, setIsSubmitError] = React.useState(false);

  // Both "create from scratch" and "import" write through the same route action as every other
  // write in this cluster (see AdminTasks.tsx/WorkspaceTasks.tsx) - `apply` for a JSON body,
  // `applyYaml` for a raw yaml/text body. `pendingRef` records which of the two is in flight (and
  // the modal's closeModal) so the effect below knows what to do once the fetcher settles -
  // mirrors TaskTemplateOverview.tsx's PendingApply.
  const fetcher = useFetcher<
    | { ok: true; intent: "apply" | "applyYaml"; task: Task }
    | { ok: false; intent: "apply" | "applyYaml"; error: { title: string; message: string } }
  >();
  const pendingRef = React.useRef<{ kind: "create" | "import"; closeModal: () => void } | null>(null);

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || (fetcher.data.intent !== "apply" && fetcher.data.intent !== "applyYaml")) {
      return;
    }
    const pending = pendingRef.current;
    pendingRef.current = null;
    if (!pending) {
      return;
    }
    const result = fetcher.data;
    setIsSubmitting(false);

    if (!result.ok) {
      setIsSubmitError(true);
      return;
    }

    setIsSubmitError(false);
    const verb = pending.kind === "create" ? "created" : "imported";
    notify(
      <ToastNotification
        kind="success"
        subtitle={`Successfully ${verb} task template`}
        title={`Task Template ${result.task.displayName} ${verb}`}
        data-testid={pending.kind === "create" ? "create-task-template-notification" : "import-task-template-notification"}
      />,
    );
    navigate(
      params.workspace
        ? appLink.manageTasksEdit({ workspace: params.workspace, name: result.task.name, version: String(result.task.version) })
        : appLink.adminTasksDetail({ name: result.task.name, version: String(result.task.version) }),
    );
    pending.closeModal();
  }, [fetcher.state, fetcher.data]);

  const handleAddTaskTemplate = ({ name, replace, body, closeModal }) => {
    setIsSubmitting(true);
    pendingRef.current = { kind: "create", closeModal };
    fetcher.submit({ intent: "apply", name, replace, body: JSON.stringify(body) }, { method: "post" });
  };

  const handleImportTaskTemplate = ({ type, name, replace, body, closeModal }) => {
    setIsSubmitting(true);
    pendingRef.current = { kind: "import", closeModal };
    if (type === "application/json") {
      fetcher.submit({ intent: "apply", name, replace, body: JSON.stringify(body) }, { method: "post" });
    } else {
      fetcher.submit({ intent: "applyYaml", name, replace, body: JSON.stringify(body) }, { method: "post" });
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
