//@ts-nocheck
import React from "react";
import { Button, InlineNotification, Tag, Tile } from "@carbon/react";
import { Draggable as DraggableIcon, TrashCan, Bee } from "@carbon/react/icons";
import { Loading, notify, ToastNotification, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { sentenceCase } from "change-case";
import { log } from "console";
import { Formik } from "formik";
import fileDownload from "js-file-download";
import { DragDropContext, Droppable, Draggable } from "react-beautiful-dnd";
import { Helmet } from "react-helmet";
import { useMutation, useQueryClient } from "react-query";
import { useHistory, Prompt, matchPath, useParams } from "react-router-dom";
import { Box } from "reflexbox";
import EditTaskTemplateModal from "Components/EditTaskTemplateModal";
import EmptyState from "Components/EmptyState";
import PreviewConfig from "Components/PreviewConfig";
import TemplateConfigModal from "Components/TemplateConfigModal";
import TemplateParametersModal from "Components/TemplateParametersModal";
import WombatMessage from "Components/WombatMessage";
import { useQuery } from "Hooks";
import { taskIcons } from "Utils/taskIcons";
import { TaskTemplateStatus } from "Constants";
import { appLink, AppPath } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { DataDrivenInput, Task } from "Types";
import Header from "../Header";
import { TemplateRequestType, FieldTypes } from "../constants";
import styles from "./TaskTemplateOverview.module.scss";

interface DetailDataElementsProps {
  label: string;
  value: string;
}

const DetailDataElements: React.FC<DetailDataElementsProps> = ({ label, value }) => {
  const TaskIcon = taskIcons.find((icon) => icon.name === value);

  if (label === "Envs") {
    return (
      <section className={styles.infoSection}>
        <dt className={styles.label}>{label}</dt>
        <dd className={value?.length ? styles.value : styles.noValue} data-testid={label}>
          {value?.length > 0
            ? value.map((env) => {
                return <Tag>{`${env.name}:${env.value}`}</Tag>;
              })
            : "Not defined yet"}
        </dd>
      </section>
    );
  }

  return (
    <section className={styles.infoSection}>
      <dt className={styles.label}>{label}</dt>
      {label === "Icon" ? (
        TaskIcon ? (
          <div className={styles.basicIcon}>
            <TaskIcon.Icon style={{ width: "1.5rem", height: "1.5rem", marginRight: "0.75rem" }} />
            <p className={styles.value}>{TaskIcon.name}</p>
          </div>
        ) : (
          <div className={styles.basicIcon}>
            <Bee style={{ width: "1rem", height: "1rem", marginRight: "0.75rem" }} />
            <p className={styles.value}>Default</p>
          </div>
        )
      ) : (
        <dd className={value ? styles.value : styles.noValue} data-testid={label}>
          {value ? value : "Not defined yet"}
        </dd>
      )}
    </section>
  );
};

interface FieldProps {
  field: any;
  innerRef: any;
  draggableProps: any;
  dragHandleProps: any;
  setFieldValue: any;
  fields: any;
  deleteConfiguration: any;
  isOldVersion: any;
  isActive: any;
  canEdit: boolean;
}

const Field: React.FC<FieldProps> = ({
  field,
  innerRef,
  draggableProps,
  dragHandleProps,
  setFieldValue,
  fields,
  deleteConfiguration,
  isOldVersion,
  isActive,
  canEdit,
}) => {
  return (
    <section className={styles.fieldSection} ref={innerRef} {...draggableProps}>
      <div
        className={styles.iconContainer}
        {...dragHandleProps}
        style={{ display: `${isOldVersion || !isActive ? "none" : "flex"}` }}
      >
        <DraggableIcon className={styles.dragabble} />
      </div>
      <dd
        className={styles.value}
        data-testid={field.label}
        style={{ marginLeft: `${isOldVersion || !isActive ? "1.5rem" : "0"}` }}
      >
        {`${FieldTypes[field.type]} | ${field.label} - ${field.name}`}
      </dd>
      <div className={styles.actions}>
        <TemplateConfigModal
          isActive={isActive}
          isEdit
          field={field}
          isOldVersion={isOldVersion}
          setFieldValue={setFieldValue}
          templateFields={fields}
          canEdit={canEdit}
        />
        <TooltipHover direction="bottom" tooltipText={"Delete field"}>
          <Button
            className={styles.delete}
            disabled={isOldVersion || !isActive || !canEdit}
            iconDescription="delete-field"
            kind="ghost"
            onClick={() => deleteConfiguration(field)}
            renderIcon={TrashCan}
            size="md"
          />
        </TooltipHover>
      </div>
    </section>
  );
};

interface ResultProps {
  result: any;
  setFieldValue: any;
  results: any;
  DeleteResult: any;
  isOldVersion: any;
  isActive: any;
  canEdit: boolean;
  index: number;
  resultKeys: string[];
}

const Result: React.FC<ResultProps> = ({
  result,
  setFieldValue,
  results,
  DeleteResult,
  isOldVersion,
  isActive,
  canEdit,
  index,
  resultKeys,
}) => {
  return (
    <section className={styles.fieldSection}>
      <dd
        className={styles.value}
        data-testid={result.name}
        // style={{ marginLeft: `${isOldVersion || !isActive ? "1.5rem" : "0"}` }}
        style={{ paddingLeft: `1rem` }}
      >
        {`${result.name} | ${result.description}`}
      </dd>
      <div className={styles.actions}>
        <TemplateParametersModal
          result={result}
          isEdit
          index={index}
          resultKeys={resultKeys}
          setFieldValue={setFieldValue}
          templateFields={results}
          isOldVersion={isOldVersion}
          isActive={isActive}
          canEdit={canEdit}
        />
        <TooltipHover direction="bottom" tooltipText={"Delete result paramater"}>
          <Button
            className={styles.delete}
            disabled={isOldVersion || !isActive || !canEdit}
            iconDescription="delete-parameter"
            kind="ghost"
            onClick={() => DeleteResult(index)}
            renderIcon={TrashCan}
            size="md"
          />
        </TooltipHover>
      </div>
    </section>
  );
};

type TaskOverviewProps = {
  taskTemplates: Array<Task>;
  getTaskTemplatesUrl: string;
  editVerifiedTasksEnabled: any;
};

export function TaskTemplateOverview({
  taskTemplates,
  getTaskTemplatesUrl,
  editVerifiedTasksEnabled,
}: TaskOverviewProps) {
  const [isSaving, setIsSaving] = React.useState(false);
  const queryClient = useQueryClient();
  const params = useParams();
  const history = useHistory();

  let getTaskTemplateUrl = serviceUrl.task.getTask({
    name: params.name,
    version: params.version,
  });
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
  const getTaskTemplateQuery = useQuery(getTaskTemplateUrl);
  const getChangelogQuery = useQuery<ChangeLog>(getChangelogUrl);
  const applyTaskTemplateMutation = useMutation(resolver.putApplyTaskTemplate);
  const applyTeamTaskTemplateMutation = useMutation(resolver.putApplyTeamTaskTemplate);

  if (getTaskTemplateQuery.isLoading || getChangelogQuery.isLoading) {
    return (
      <div className={styles.container}>
        <Box maxWidth="24rem" margin="0 auto">
          <WombatMessage className={styles.wombat} title="Retrieving Tasks..." />
        </Box>
      </div>
    );
  }

  if (getTaskTemplateQuery.error || getChangelogQuery.error) {
    return (
      <EmptyState title="Task Template not found" message="Crikey. We can't find the template you are looking for." />
    );
  }
  const selectedTaskTemplate = getTaskTemplateQuery.data;
  const canEdit = !selectedTaskTemplate?.verified || (editVerifiedTasksEnabled && selectedTaskTemplate?.verified);
  const isActive = selectedTaskTemplate.status === TaskTemplateStatus.Active;
  // params.version is a string, getChangelogQuery.data.length is a number
  const isOldVersion = Boolean(params.version < getChangelogQuery.data.length);
  const fieldKeys = selectedTaskTemplate.spec.params?.map((input: DataDrivenInput) => input.name) ?? [];
  const resultKeys = selectedTaskTemplate.spec.results?.map((input: DataDrivenInput) => input.name) ?? [];

  const reorder = (list, startIndex, endIndex) => {
    const result = Array.from(list);
    const [removed] = result.splice(startIndex, 1);
    result.splice(endIndex, 0, removed);
    return result;
  };

  const handleSaveTaskTemplate = async (values, resetForm, requestType, setRequestError, closeModal) => {
    setIsSaving(true);
    console.log("Request Type:", requestType);
    let newVersion =
      requestType === TemplateRequestType.Overwrite ? selectedTaskTemplate.version : getChangelogQuery.data.length + 1;
    let changeReason =
      requestType === TemplateRequestType.Copy
        ? `Version copied from ${values.currentConfig.version}`
        : values.comments;
    let newEnvs = values.envs
      ? values.envs.map((env) => {
          let index = env.indexOf(":");
          return { name: env.substring(0, index), value: env.substring(index + 1, env.length) };
        })
      : selectedTaskTemplate.spec.envs;
    const spec = {
      arguments: Boolean(values.arguments)
        ? values.arguments.trim().split(/\n{1,}/)
        : selectedTaskTemplate.spec.arguments,
      command: Boolean(values.command) ? values.command.trim().split(/\n{1,}/) : selectedTaskTemplate.spec.command,
      envs: newEnvs,
      image: values.image ? values.image : selectedTaskTemplate.spec.image,
      params: Boolean(values.currentConfig) ? values.currentConfig : selectedTaskTemplate.spec.params,
      results: Boolean(values.result) ? values.result : selectedTaskTemplate.spec.results,
      script: values.script ? values.script : selectedTaskTemplate.spec.script,
      workingDir: values.workingDir ? values.workingDir : selectedTaskTemplate.spec.workingDir,
    };
    const body: Task = {
      name: selectedTaskTemplate.name,
      displayName: values.displayName ? values.displayName : selectedTaskTemplate.displayName,
      description: values.description ? values.description : selectedTaskTemplate.description,
      status: "active",
      category: values.category ? values.category : selectedTaskTemplate.category,
      version: newVersion,
      icon: values.icon ? values.icon : selectedTaskTemplate.icon,
      type: "template",
      changelog: { reason: changeReason },
      spec: spec,
    };

    try {
      let replace = requestType === TemplateRequestType.Overwrite ? "true" : "false";
      let response;
      console.log("Name:", params.name);
      if (params.team) {
        response = await applyTeamTaskTemplateMutation.mutateAsync({
          name: params.name,
          team: params.team,
          replace,
          body,
        });
      } else {
        response = await applyTaskTemplateMutation.mutateAsync({ name: params.name, replace, body });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      await queryClient.invalidateQueries(serviceUrl.getFeatureFlags());
      notify(
        <ToastNotification
          kind="success"
          title={"Task Template Updated"}
          subtitle={`Request to update ${body.displayName} succeeded`}
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
          : appLink.adminTasksDetail({
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
          name: params.name,
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ replace: "true", name: params.name, body: selectedTaskTemplate });
      }
      await queryClient.invalidateQueries(getTaskTemplateUrl);
      await queryClient.invalidateQueries(getChangelogUrl);
      await queryClient.invalidateQueries(serviceUrl.getFeatureFlags());
      notify(
        <ToastNotification
          kind="success"
          title={"Successfully Archived Task Template"}
          subtitle={`Request to archive ${params.name} succeeded`}
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
          name: params.name,
          body: selectedTaskTemplate,
        });
      } else {
        await applyTaskTemplateMutation.mutateAsync({ replace: "true", name: params.name, body: selectedTaskTemplate });
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

  //TODO should this handle JSON and YAML?
  const handleDownloadTaskTemplate = async () => {
    try {
      fileDownload(JSON.stringify(selectedTaskTemplate), `${selectedTaskTemplate.name}.json`);
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
        name: selectedTaskTemplate.name,
        displayName: selectedTaskTemplate.displayName,
        description: selectedTaskTemplate.description,
        icon: selectedTaskTemplate.icon,
        category: selectedTaskTemplate.category,
        image: selectedTaskTemplate.spec.image,
        currentConfig: selectedTaskTemplate.spec.params ?? [],
        arguments: Array.isArray(selectedTaskTemplate.spec.arguments)
          ? selectedTaskTemplate.spec.arguments?.join("\n")
          : selectedTaskTemplate.spec.arguments ?? "",
        command: Array.isArray(selectedTaskTemplate.spec.command)
          ? selectedTaskTemplate.spec.command?.join("\n")
          : selectedTaskTemplate.spec.command ?? "",
        script: selectedTaskTemplate.spec.script ?? "",
        workingDir: selectedTaskTemplate.spec.workingDir ?? "",
        result: selectedTaskTemplate.spec.results ?? [],
        envs: selectedTaskTemplate.spec.envs ?? [],
        comments: "",
      }}
      enableReinitialize={true}
    >
      {(formikProps) => {
        const { setFieldValue, values, dirty: isDirty, isSubmitting } = formikProps;

        function deleteConfiguration(selectedField) {
          const configIndex = values.currentConfig.findIndex((field) => field.name === selectedField.name);
          let newProperties = [].concat(values.currentConfig);
          newProperties.splice(configIndex, 1);
          setFieldValue("currentConfig", newProperties);
        }
        function DeleteResult(index) {
          let newResults = [].concat(values.result);
          newResults.splice(index, 1);
          setFieldValue("result", newResults);
        }
        const onDragEnd = async (result) => {
          if (result.source && result.destination) {
            const newFields = reorder(values.currentConfig, result.source.index, result.destination.index);
            setFieldValue("currentConfig", newFields);
          }
        };
        return (
          <div className={styles.container}>
            <Helmet>
              <title>{`Task manager - ${selectedTaskTemplate.displayName}`}</title>
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
                if (
                  isDirty &&
                  templateMatch?.params?.version !== selectedTaskTemplate.currentVersion &&
                  !isSubmitting
                ) {
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
              <div className={styles.detailCardsContainer}>
                <Tile className={styles.editDetailsCard}>
                  <section className={styles.editTitle}>
                    <h1>Basics</h1>
                    <EditTaskTemplateModal
                      taskTemplates={taskTemplates}
                      setFieldValue={setFieldValue}
                      fields={values.currentConfig}
                      values={values}
                      isOldVersion={isOldVersion}
                      isActive={isActive}
                      canEdit={canEdit}
                    />
                  </section>
                  <dl className={styles.detailsDataList}>
                    <DetailDataElements value={values.name} label="Name" />
                    <DetailDataElements value={values.displayName} label="Display Name" />
                    <DetailDataElements value={values.category} label="Category" />
                    <DetailDataElements value={values.icon} label="Icon" />
                    <DetailDataElements value={values.description} label="Description" />
                    <DetailDataElements value={values.image} label="Image" />
                    <DetailDataElements value={values.command} label="Command" />
                    <DetailDataElements value={values.arguments} label="Arguments" />
                    <DetailDataElements value={values.script} label="Script" />
                    <DetailDataElements value={values.workingDir} label="Working Directory" />
                    <DetailDataElements value={values.envs} label="Envs" />
                  </dl>
                </Tile>
                <Tile className={styles.editFieldsCard}>
                  <section className={styles.editTitle}>
                    <hgroup className={styles.fieldsTitle}>
                      <h1>Parameter fields</h1>
                      <h2 className={styles.fieldDesc}>Drag to reorder the fields</h2>
                    </hgroup>
                    <div className={styles.fieldActions}>
                      <PreviewConfig templateConfig={values.currentConfig} taskTemplateName={values.name} />
                      <TemplateConfigModal
                        fieldKeys={fieldKeys}
                        setFieldValue={setFieldValue}
                        templateFields={values.currentConfig}
                        isOldVersion={isOldVersion}
                        isActive={isActive}
                        canEdit={canEdit}
                      />
                    </div>
                  </section>
                  <DragDropContext onDragEnd={onDragEnd}>
                    <Droppable droppableId="droppable" direction="vertical">
                      {(provided) => (
                        <div className={styles.fieldsContainer} ref={provided.innerRef}>
                          {values.currentConfig?.length > 0 ? (
                            values.currentConfig.map((field, index) => (
                              <Draggable key={field.name} draggableId={field.name} index={index}>
                                {(provided) => (
                                  <Field
                                    field={field}
                                    dragHandleProps={provided.dragHandleProps}
                                    draggableProps={provided.draggableProps}
                                    innerRef={provided.innerRef}
                                    setFieldValue={setFieldValue}
                                    fields={values.currentConfig}
                                    deleteConfiguration={deleteConfiguration}
                                    isOldVersion={isOldVersion}
                                    isActive={isActive}
                                    canEdit={canEdit}
                                  />
                                )}
                              </Draggable>
                            ))
                          ) : (
                            <div className={styles.noFieldsContainer}>
                              <p className={styles.noFieldsTitle}>No parameters (yet)</p>
                              <p className={styles.noFieldsText}>
                                Fields determine the parameters of a task, defining what is passed to the task and
                                prompting users to fill in their values and messages.
                              </p>
                              <p className={styles.noFieldsText}>Add a field above to get started.</p>
                            </div>
                          )}
                          {provided.placeholder}
                        </div>
                      )}
                    </Droppable>
                  </DragDropContext>
                </Tile>
                <Tile className={styles.editFieldsCard}>
                  <section className={styles.editTitleParameters}>
                    <h1>Result Parameters</h1>
                    <TemplateParametersModal
                      resultKeys={resultKeys}
                      setFieldValue={setFieldValue}
                      templateFields={values.result}
                      isOldVersion={isOldVersion}
                      isActive={isActive}
                      canEdit={canEdit}
                    />
                  </section>
                  <div className={styles.fieldsContainer}>
                    {values.result?.length > 0 ? (
                      values.result.map((result, index) => (
                        <Result
                          key={result.name}
                          result={result}
                          setFieldValue={setFieldValue}
                          results={values.result}
                          DeleteResult={DeleteResult}
                          isOldVersion={isOldVersion}
                          isActive={isActive}
                          canEdit={canEdit}
                          index={index}
                        />
                      ))
                    ) : (
                      <div className={styles.noFieldsContainer}>
                        <p className={styles.noFieldsTitle}>No Result Paramaters (yet)</p>
                        <p className={styles.noFieldsText}>
                          Result Parameters map to the output of a task. Provide the name and description for the
                          variables that will be output as a results of this task.
                        </p>
                        <p className={styles.noFieldsText}>Add a result paramater above to get started.</p>
                      </div>
                    )}
                  </div>
                </Tile>
              </div>
            </div>
          </div>
        );
      }}
    </Formik>
  );
}

export default TaskTemplateOverview;
