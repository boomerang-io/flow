//@ts-nocheck
import React, { useState } from "react";
import { InlineNotification } from "@carbon/react";
import { ChevronRight } from "@carbon/react/icons";
import { Loading, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import "Styles/markdown.css";
import axios from "axios";
import { sentenceCase } from "change-case";
import cx from "classnames";
import "codemirror/addon/comment/comment.js";
import "codemirror/addon/fold/brace-fold.js";
import "codemirror/addon/fold/comment-fold.js";
import "codemirror/addon/fold/foldcode.js";
import "codemirror/addon/fold/foldgutter.css";
import "codemirror/addon/fold/foldgutter.js";
import "codemirror/addon/fold/indent-fold.js";
import "codemirror/addon/hint/javascript-hint";
import "codemirror/addon/hint/show-hint";
import "codemirror/addon/search/searchcursor";
import "codemirror/lib/codemirror.css";
import "codemirror/mode/yaml/yaml";
import "codemirror/theme/material.css";
import { Formik } from "formik";
import fileDownload from "js-file-download";
// import CodeMirror from "codemirror";
import { Controlled as CodeMirrorReact } from "react-codemirror2";
import { Helmet } from "react-helmet";
import ReactMarkdown from "react-markdown";
import { useFetcher, useParams, useNavigate, useBlocker, matchPath, useRevalidator } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { TaskTemplateStatus } from "Constants";
import { yamlInstructions } from "Constants";
import { appLink, AppPath } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { ChangeLog, Task } from "Types";
import Header from "../Header";
import { TemplateRequestType } from "../constants";
import styles from "./TaskTemplateEditor.module.scss";

type TaskYamlEditorProps = {
  editVerifiedTasksEnabled: any;
  selectedTaskTemplate: Task | null;
  changelog: ChangeLog | null;
  yaml: string | null;
  errorLoading: boolean;
};

// useBlocker must be called from its own component so it keeps a stable hook position
// regardless of how Formik invokes the surrounding render-prop function.
function TaskTemplateEditorBlocker({ getBlockMessage }: { getBlockMessage: (pathname: string) => string | null }) {
  const blocker = useBlocker(({ nextLocation }) => getBlockMessage(nextLocation.pathname) !== null);

  React.useEffect(() => {
    if (blocker.state === "blocked") {
      const message = getBlockMessage(blocker.location.pathname) ?? "Are you sure you want to leave? You have unsaved changes.";
      if (window.confirm(message)) {
        blocker.proceed();
      } else {
        blocker.reset();
      }
    }
  }, [blocker, getBlockMessage]);

  return null;
}

// Discriminates what a pending fetcher submission should do once it settles - mirrors
// TaskTemplateOverview.tsx's PendingApply. "save" covers both the JSON `apply` (Copy) and the
// text `applyYaml` (Overwrite/New version) writes, since both settle the same modal flow.
type PendingWrite =
  | {
      kind: "save";
      requestType: string;
      resetForm: () => void;
      setRequestError: (error: { title: string; subtitle: string } | null) => void;
      closeModal: () => void;
    }
  | { kind: "archive" }
  | { kind: "restore" };

export function TaskTemplateYamlEditor({
  editVerifiedTasksEnabled,
  selectedTaskTemplate,
  changelog,
  yaml,
  errorLoading,
}: TaskYamlEditorProps) {
  const [isSaving, setIsSaving] = React.useState(false);
  // Feature flags are now read via the root loader (Features/App/App.tsx), not a react-query
  // cache entry - queryClient.invalidateQueries(getFeatureFlags()) below would be a silent
  // no-op, so those call sites revalidate() instead. The yaml text, task template and changelog
  // all come from the parent route's loader as props now (see AdminTasks.tsx/WorkspaceTasks.tsx).
  const revalidator = useRevalidator();
  const fetcher = useFetcher<
    | { ok: true; intent: "apply" | "applyYaml"; task: Task }
    | { ok: false; intent: "apply" | "applyYaml"; error: { title: string; message: string } }
  >();
  const pendingWriteRef = React.useRef<PendingWrite | null>(null);

  const params = useParams();
  const navigate = useNavigate();

  const [docOpen, setDocOpen] = useState(true);

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || (fetcher.data.intent !== "apply" && fetcher.data.intent !== "applyYaml")) {
      return;
    }
    const pending = pendingWriteRef.current;
    pendingWriteRef.current = null;
    if (!pending) {
      return;
    }
    const result = fetcher.data;

    if (pending.kind === "archive") {
      revalidator.revalidate();
      notify(
        result.ok ? (
          <ToastNotification
            kind="success"
            title={"Successfully Archived Task Template"}
            subtitle={`Request to archive ${params.name} succeeded`}
            data-testid="archive-task-template-notification"
          />
        ) : (
          <ToastNotification
            kind="error"
            title={"Archive Task Template Failed"}
            subtitle={result.error.message}
            data-testid="archive-task-template-notification"
          />
        ),
      );
      return;
    }

    if (pending.kind === "restore") {
      revalidator.revalidate();
      notify(
        result.ok ? (
          <ToastNotification
            kind="success"
            title={"Successfully Restored Task Template"}
            subtitle={`Request to restore ${selectedTaskTemplate?.name} succeeded`}
            data-testid="restore-task-template-notification"
          />
        ) : (
          <ToastNotification
            kind="error"
            title={"Restore Task Template Failed"}
            subtitle={result.error.message}
            data-testid="restore-task-template-notification"
          />
        ),
      );
      return;
    }

    // pending.kind === "save"
    setIsSaving(false);
    if (result.ok) {
      revalidator.revalidate();
      notify(
        <ToastNotification
          kind="success"
          title={"Task Template Updated"}
          subtitle={`Request to update succeeded`}
          data-testid="create-update-task-template-notification"
        />,
      );
      pending.resetForm();
      navigate(
        params.workspace
          ? appLink.manageTasksEdit({ workspace: params.workspace, name: result.task.name, version: String(result.task.version) })
          : appLink.adminTasksDetail({ name: result.task.name, version: String(result.task.version) }),
      );
      if (pending.requestType !== TemplateRequestType.Copy) {
        pending.setRequestError(null);
        pending.closeModal();
      }
    } else {
      if (pending.requestType !== TemplateRequestType.Copy) {
        pending.setRequestError({ title: result.error.title, subtitle: result.error.message });
      } else {
        notify(
          <ToastNotification
            kind="error"
            title={"Update Task Template Failed"}
            subtitle={"Something's Wrong"}
            data-testid="update-task-template-notification"
          />,
        );
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  if (errorLoading || !selectedTaskTemplate || !changelog || yaml === null) {
    return (
      <EmptyState title="Task Template not found" message="Crikey. We can't find the template you are looking for." />
    );
  }

  const canEdit = !selectedTaskTemplate?.verified || (editVerifiedTasksEnabled && selectedTaskTemplate?.verified);
  const isActive = selectedTaskTemplate.status === TaskTemplateStatus.Active;
  // params.version is a string, changelog.length is a number
  const isOldVersion = params.version < changelog.length;

  const handleSaveTaskTemplate = (values, resetForm, requestType, setRequestError, closeModal) => {
    setIsSaving(true);
    pendingWriteRef.current = { kind: "save", requestType, resetForm, setRequestError, closeModal };

    if (requestType === TemplateRequestType.Copy) {
      const body = {
        ...selectedTaskTemplate,
        version: changelog.length + 1,
        // eslint-disable-next-line no-template-curly-in-string
        changelog: { reason: "Version copied from ${values.currentConfig.version}" },
      };
      fetcher.submit(
        { intent: "apply", name: selectedTaskTemplate.name, replace: "false", body: JSON.stringify(body) },
        { method: "post" },
      );
    } else {
      const replace = requestType === TemplateRequestType.Overwrite;
      fetcher.submit(
        { intent: "applyYaml", name: selectedTaskTemplate.name, replace: String(replace), body: values.yaml },
        { method: "post" },
      );
    }
  };

  const handleArchiveTaskTemplate = () => {
    pendingWriteRef.current = { kind: "archive" };
    fetcher.submit(
      {
        intent: "apply",
        name: selectedTaskTemplate.name,
        replace: "true",
        body: JSON.stringify({ ...selectedTaskTemplate, status: "inactive" }),
      },
      { method: "post" },
    );
  };

  const handleRestoreTaskTemplate = () => {
    pendingWriteRef.current = { kind: "restore" };
    fetcher.submit(
      {
        intent: "apply",
        name: selectedTaskTemplate.name,
        replace: "true",
        body: JSON.stringify({ ...selectedTaskTemplate, status: "active" }),
      },
      { method: "post" },
    );
  };

  const handleDownloadTaskTemplate = async () => {
    try {
      let url = serviceUrl.task.getTask({ name: selectedTaskTemplate.name, version: selectedTaskTemplate.version });
      if (params.workspace) {
        url = serviceUrl.workspace.task.getTask({
          workspace: params.workspace,
          name: selectedTaskTemplate.name,
          version: selectedTaskTemplate.version,
        });
      }
      const response = await axios.get(url, {
        headers: { Accept: "application/x-yaml" },
      });
      fileDownload(response.data, `${selectedTaskTemplate.name}.yaml`);
      notify(
        <ToastNotification
          kind="success"
          title={"Task Template Download"}
          subtitle={`Request to download ${params.name} started.`}
          data-testid="downloaded-task-template-notification"
        />,
      );
    } catch (err) {
      notify(
        <ToastNotification
          kind="error"
          title={"Download Task Template Failed"}
          subtitle={`Unable to download the task template. ${sentenceCase(err.message)}. Please contact support.`}
          data-testid="download-task-template-notification"
        />,
      );
    }
  };

  return (
    <Formik
      initialValues={{
        yaml: yaml ?? "",
      }}
      enableReinitialize={true}
    >
      {(formikProps) => {
        const { setFieldValue, values, dirty: isDirty, isSubmitting } = formikProps;

        // Same in-app "leave without saving" guard as before, ported from v5's <Prompt> to
        // v6/v7's useBlocker (requires the data router set up in Root.tsx). Returns the
        // confirm-dialog message to show for a given target pathname, or null to navigate
        // through unprompted.
        function getBlockMessage(pathname: string) {
          const templateMatch = matchPath({ path: AppPath.TaskTemplateDetail }, pathname);
          if (isDirty && !pathname.includes(templateMatch?.params?.id) && !isSubmitting) {
            return "Are you sure you want to leave? You have unsaved changes.";
          }
          if (isDirty && templateMatch?.params?.version !== selectedTaskTemplate.version && !isSubmitting) {
            return "Are you sure you want to change the version? Your changes will be lost.";
          }
          return null;
        }

        return (
          <div className={styles.container}>
            <Helmet>
              <title>{`Task manager - ${selectedTaskTemplate.name}`}</title>
            </Helmet>
            <TaskTemplateEditorBlocker getBlockMessage={getBlockMessage} />
            {(fetcher.state !== "idle" || isSaving) && <Loading />}
            <Header
              editVerifiedTasksEnabled={editVerifiedTasksEnabled}
              selectedTaskTemplate={selectedTaskTemplate}
              changelog={changelog}
              formikProps={formikProps}
              handleRestoreTaskTemplate={handleRestoreTaskTemplate}
              handleArchiveTaskTemplate={handleArchiveTaskTemplate}
              handleSaveTaskTemplate={handleSaveTaskTemplate}
              handleDownloadTaskTemplate={handleDownloadTaskTemplate}
              isActive={isActive}
              isLoading={isSubmitting || isSaving}
              isOldVersion={isOldVersion}
            />
            <div className={styles.content}>
              {!canEdit && (
                <section className={styles.notificationsContainer}>
                  <InlineNotification
                    lowContrast
                    hideCloseButton={true}
                    kind="info"
                    title="Verified tasks are not editable"
                    subtitle="Admins can adjust this in global settings"
                  />
                </section>
              )}
              <section className={styles.yamlContainer}>
                <CodeMirrorReact
                  className={cx(styles.codeMirrorContainer, { [styles.yamlCollapsed]: !docOpen })}
                  value={values.yaml}
                  options={{
                    mode: "yaml",
                    theme: "material",
                    lineWrapping: true,
                    foldGutter: true,
                    lineNumbers: true,
                    gutters: ["CodeMirrorReact-linenumbers", "CodeMirror-foldgutter"],
                  }}
                  onBeforeChange={(editor, data, value) => {
                    setFieldValue("yaml", value);
                  }}
                />
                <div className={cx(styles.markdownContainer, { [styles.collapsed]: !docOpen })}>
                  <button className={styles.collapseButton} onClick={() => setDocOpen(!docOpen)}>
                    <ChevronRight size={32} className={styles.collapseButtonImg} />
                  </button>
                  {docOpen && <ReactMarkdown className="markdown-body" children={yamlInstructions} />}
                </div>
              </section>
            </div>
          </div>
        );
      }}
    </Formik>
  );
}

export default TaskTemplateYamlEditor;
