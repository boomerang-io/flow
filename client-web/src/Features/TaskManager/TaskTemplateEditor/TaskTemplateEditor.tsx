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
import { useParams, useHistory, Prompt, matchPath } from "react-router-dom";
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

export function TaskTemplateYamlEditor({
  taskTemplates,
  editVerifiedTasksEnabled,
  getTaskTemplatesUrl,
}: TaskYamlEditorProps) {
  const [isSaving, setIsSaving] = React.useState(false);
  const queryClient = useQueryClient();

  const params = useParams();
  const history = useHistory();

  const [docOpen, setDocOpen] = useState(true);

  let getTaskTemplateUrl = serviceUrl.task.getTask({ name: params.name, version: params.version });
  let getChangelogUrl = serviceUrl.task.getTaskChangelog({
    name: params.name,
  });
  if (params.team) {
    getTaskTemplateUrl = serviceUrl.team.task.getTask({
      team: params.team,
      name: params.name,
      version: params.version,
    });
    getChangelogUrl = serviceUrl.team.task.getTaskChangelog({
      team: params.team,
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
  const applyTeamTaskTemplateMutation = useMutation(resolver.putApplyTeamTaskTemplate);
  const applyTeamTaskTemplateYamlMutation = useMutation(resolver.putApplyTeamTaskTemplateYaml);

  if (
    getTaskTemplateYamlQuery.isLoading ||
    getChangelogQuery.isLoading ||
    applyTaskTemplateYamlMutation.isLoading ||
    applyTeamTaskTemplateYamlMutation.isLoading
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
        if (params.team) {
          response = await applyTeamTaskTemplateMutation.mutateAsync({
            team: params.team,
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
        if (params.team) {
          response = await applyTeamTaskTemplateYamlMutation.mutateAsync({
            replace: replace,
            team: params.team,
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
      queryClient.invalidateQueries(serviceUrl.getFeatureFlags());
      notify(
        <ToastNotification
          kind="success"
          title={"Task Template Updated"}
          subtitle={`Request to update succeeded`}
          data-testid="create-update-task-template-notification"
        />,
      );
      resetForm();
      history.push(
        params.team
          ? appLink.manageTasksEdit({
              team: params.team,
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
      if (params.team) {
        await applyTeamTaskTemplateMutation.mutateAsync({
          replace: "true",
          team: params.team,
          name: selectedTaskTemplate.name, 
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ replace: "true", name: selectedTaskTemplate.name, body: selectedTaskTemplate });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      await queryClient.invalidateQueries(serviceUrl.getFeatureFlags());
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
      if (params.team) {
        await applyTeamTaskTemplateMutation.mutateAsync({
          replace: "true",
          team: params.team,
          name: selectedTaskTemplate.name, 
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ name: selectedTaskTemplate.name, replace: "true", body: selectedTaskTemplate });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      await queryClient.invalidateQueries(serviceUrl.getFeatureFlags());
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
      if (params.team) {
        url = serviceUrl.team.task.getTask({
          team: params.team,
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

        return (
          <div className={styles.container}>
            <Helmet>
              <title>{`Task manager - ${selectedTaskTemplate.name}`}</title>
            </Helmet>
            <Prompt
              message={(location) => {
                let prompt = true;
                const templateMatch = matchPath(location.pathname, {
                  path: AppPath.TaskTemplateDetail,
                });
                if (isDirty && !location.pathname.includes(templateMatch?.params?.id) && !isSubmitting) {
                  prompt = "Are you sure you want to leave? You have unsaved changes.";
                }
                if (isDirty && templateMatch?.params?.version !== selectedTaskTemplate.version && !isSubmitting) {
                  prompt = "Are you sure you want to change the version? Your changes will be lost.";
                }
                return prompt;
              }}
            />
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
