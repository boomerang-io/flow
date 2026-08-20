//@ts-nocheck
import React, { useState } from "react";
import { InlineNotification } from "@carbon/react";
import { ChevronRight } from "@carbon/react/icons";
import { Loading, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
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
import { useMutation, useQueryClient } from "react-query";
import { useParams, useNavigate, useBlocker, matchPath, useRevalidator } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { useQuery } from "Hooks";
import { TaskTemplateStatus } from "Constants";
import { yamlInstructions } from "Constants";
import { appLink, AppPath } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { Task } from "Types";
import Header from "../Header";
import { TemplateRequestType } from "../constants";
import styles from "./TaskTemplateEditor.module.scss";

type TaskYamlEditorProps = {
  taskTemplates: Array<Task>;
  editVerifiedTasksEnabled: any;
  getTaskTemplatesUrl: string;
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

export function TaskTemplateYamlEditor({
  taskTemplates,
  editVerifiedTasksEnabled,
  getTaskTemplatesUrl,
}: TaskYamlEditorProps) {
  const [isSaving, setIsSaving] = React.useState(false);
  const queryClient = useQueryClient();
  // Feature flags are now read via the root loader (Features/App/App.tsx), not a react-query
  // cache entry - queryClient.invalidateQueries(getFeatureFlags()) below would be a silent
  // no-op, so those call sites revalidate() instead.
  const revalidator = useRevalidator();

  const params = useParams();
  const navigate = useNavigate();

  const [docOpen, setDocOpen] = useState(true);

  let getTaskTemplateUrl = serviceUrl.task.getTask({ name: params.name, version: params.version });
  let getChangelogUrl = serviceUrl.task.getTaskChangelog({
    name: params.name,
  });
  if (params.workspace) {
    getTaskTemplateUrl = serviceUrl.workspace.task.getTask({
      workspace: params.workspace,
      name: params.name,
      version: params.version,
    });
    getChangelogUrl = serviceUrl.workspace.task.getTaskChangelog({
      workspace: params.workspace,
      name: params.name,
    });
  }

  const getTaskTemplateYamlQuery = useQuery({
    queryKey: [getTaskTemplateUrl, "yaml"],
    queryFn: resolver.queryYaml(getTaskTemplateUrl),
  });
  const getChangelogQuery = useQuery<ChangeLog>(getChangelogUrl);
  const applyTaskTemplateMutation = useMutation(resolver.putApplyTaskTemplate);
  const applyTaskTemplateYamlMutation = useMutation(resolver.putApplyTaskTemplateYaml);
  const applyWorkspaceTaskTemplateMutation = useMutation(resolver.putApplyWorkspaceTaskTemplate);
  const applyWorkspaceTaskTemplateYamlMutation = useMutation(resolver.putApplyWorkspaceTaskTemplateYaml);

  if (
    getTaskTemplateYamlQuery.isLoading ||
    getChangelogQuery.isLoading ||
    applyTaskTemplateYamlMutation.isLoading ||
    applyWorkspaceTaskTemplateYamlMutation.isLoading
  ) {
    return <Loading />;
  }

  if (getTaskTemplateYamlQuery.error || getChangelogQuery.error) {
    return (
      <EmptyState title="Task Template not found" message="Crikey. We can't find the template you are looking for." />
    );
  }

  const selectedTaskTemplate = taskTemplates.filter((t) => t.name === params.name)[0];
  const canEdit = !selectedTaskTemplate?.verified || (editVerifiedTasksEnabled && selectedTaskTemplate?.verified);
  const isActive = selectedTaskTemplate.status === TaskTemplateStatus.Active;
  // params.version is a string, getChangelogQuery.data.length is a number
  const isOldVersion = params.version < getChangelogQuery.data.length;

  const handleSaveTaskTemplate = async (values, resetForm, requestType, setRequestError, closeModal) => {
    setIsSaving(true);
    try {
      let response;
      if (requestType === TemplateRequestType.Copy) {
        let body = {
          ...selectedTaskTemplate,
          version: getChangelogQuery.data.length + 1,
          // eslint-disable-next-line no-template-curly-in-string
          changelog: { reason: "Version copied from ${values.currentConfig.version}" },
        };
        if (params.workspace) {
          response = await applyWorkspaceTaskTemplateMutation.mutateAsync({
            workspace: params.workspace,
            name: params.name,
            replace: false,
            body,
          });
        } else {
          response = await applyTaskTemplateMutation.mutateAsync({
            replace: false,
            name: params.name,
            body,
          });
        }
      } else {
        let replace: boolean = false;
        if (requestType === TemplateRequestType.Overwrite) {
          replace = true;
        }
        if (params.workspace) {
          response = await applyWorkspaceTaskTemplateYamlMutation.mutateAsync({
            replace: replace,
            workspace: params.workspace,
            name: params.name,
            body: values.yaml,
          });
        } else {
          response = await applyTaskTemplateYamlMutation.mutateAsync({
            replace: replace,
            name: params.name,
            body: values.yaml,
          });
        }
        queryClient.invalidateQueries([getTaskTemplateUrl, "yaml"]);
      }
      queryClient.invalidateQueries(getTaskTemplatesUrl);
      revalidator.revalidate();
      notify(
        <ToastNotification
          kind="success"
          title={"Task Template Updated"}
          subtitle={`Request to update succeeded`}
          data-testid="create-update-task-template-notification"
        />,
      );
      resetForm();
      navigate(
        params.workspace
          ? appLink.manageTasksEdit({
              workspace: params.workspace,
              name: response.data.name,
              version: response.data.version,
            })
          : appLink.taskTemplateEdit({
              name: response.data.name,
              version: response.data.version,
            }),
      );
      if (requestType !== TemplateRequestType.Copy) {
        typeof setRequestError === "function" && setRequestError(null);
        typeof closeModal === "function" && closeModal();
      }
    } catch (err) {
      if (requestType !== TemplateRequestType.Copy) {
        const { title, message: subtitle } = formatErrorMessage({
          error: err,
          defaultMessage: "Request to save task template failed.",
        });
        setRequestError({ title, subtitle });
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
    } finally {
      setIsSaving(false);
    }
  };

  const handleArchiveTaskTemplate = async () => {
    try {
      selectedTaskTemplate.status = "inactive";
      if (params.workspace) {
        await applyWorkspaceTaskTemplateMutation.mutateAsync({
          replace: "true",
          workspace: params.workspace,
          name: selectedTaskTemplate.name, 
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ replace: "true", name: selectedTaskTemplate.name, body: selectedTaskTemplate });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      revalidator.revalidate();
      notify(
        <ToastNotification
          kind="success"
          title={"Successfully Archived Task Template"}
          subtitle={`Request to archive ${selectedTaskTemplate.name} succeeded`}
          data-testid="archive-task-template-notification"
        />,
      );
    } catch (err) {
      notify(
        <ToastNotification
          kind="error"
          title={"Archive Task Template Failed"}
          subtitle={`Unable to archive the task. ${sentenceCase(err.message)}. Please contact support.`}
          data-testid="archive-task-template-notification"
        />,
      );
    }
  };

  const handleRestoreTaskTemplate = async () => {
    try {
      selectedTaskTemplate.status = "active";
      if (params.workspace) {
        await applyWorkspaceTaskTemplateMutation.mutateAsync({
          replace: "true",
          workspace: params.workspace,
          name: selectedTaskTemplate.name, 
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ name: selectedTaskTemplate.name, replace: "true", body: selectedTaskTemplate });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      revalidator.revalidate();
      notify(
        <ToastNotification
          kind="success"
          title={"Successfully Restored Task Template"}
          subtitle={`Request to restore ${selectedTaskTemplate.name} succeeded`}
          data-testid="restore-task-template-notification"
        />,
      );
    } catch (err) {
      notify(
        <ToastNotification
          kind="error"
          title={"Restore Task Template Failed"}
          subtitle={`Unable to restore the task. ${sentenceCase(err.message)}. Please contact support.`}
          data-testid="restore-task-template-notification"
        />,
      );
    }
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
      console.log("err", err);
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
        yaml: getTaskTemplateYamlQuery.data ?? "",
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
            {applyTaskTemplateMutation.isLoading && <Loading />}
            <Header
              editVerifiedTasksEnabled={editVerifiedTasksEnabled}
              selectedTaskTemplate={selectedTaskTemplate}
              changelog={getChangelogQuery.data}
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
                  //   editorDidMount={(cmeditor) => {
                  //     editor.current = cmeditor;
                  //     setDoc(cmeditor.getDoc());
                  //   }}
                  value={values.yaml}
                  options={{
                    mode: "yaml",
                    // readOnly: props.readOnly,
                    theme: "material",
                    // extraKeys: {
                    //   "Ctrl-Space": "autocomplete",
                    //   "Ctrl-Q": foldCode,
                    //   "Cmd-/": toggleComment,
                    //   "Shift-Alt-A": blockComment,
                    //   "Shift-Opt-A": blockComment,
                    // },
                    lineWrapping: true,
                    foldGutter: true,
                    lineNumbers: true,
                    gutters: ["CodeMirrorReact-linenumbers", "CodeMirror-foldgutter"],
                    // ...languageParams,
                  }}
                  onBeforeChange={(editor, data, value) => {
                    setFieldValue("yaml", value);
                  }}
                  //TB: trying to get autocomplete to work
                  //   onKeyUp={(cm, event) => {
                  //     if (
                  //       !cm.state.completionActive /*Enables keyboard navigation in autocomplete list*/ &&
                  //       event.keyCode !== 13
                  //     ) {
                  //       /*Enter - do not open autocomplete list just after item has been selected in it*/
                  //       autoComplete(cm);
                  //     }
                  //   }}
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
