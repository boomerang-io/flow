//@ts-nocheck
import React from "react";
import { Button, InlineNotification, Tag, Tile } from "@carbon/react";
import { Draggable as DraggableIcon, TrashCan, Bee } from "@carbon/react/icons";
import { Loading, notify, ToastNotification, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { sentenceCase } from "change-case";
import { Formik } from "formik";
import fileDownload from "js-file-download";
import { DragDropContext, Droppable, Draggable } from "react-beautiful-dnd";
import { Helmet } from "react-helmet";
import { useFetcher, useNavigate, useBlocker, matchPath, useParams, useRevalidator } from "react-router-dom";
import { Box } from "reflexbox";
import EditTaskTemplateModal from "Components/EditTaskTemplateModal";
import EmptyState from "Components/EmptyState";
import PreviewConfig from "Components/PreviewConfig";
import TemplateConfigModal from "Components/TemplateConfigModal";
import TemplateParametersModal from "Components/TemplateParametersModal";
import { taskIcons } from "Utils/taskIcons";
import { TaskTemplateStatus } from "Constants";
import { appLink, AppPath } from "Config/appConfig";
import { DataDrivenInput, Task, ChangeLog } from "Types";
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
  editVerifiedTasksEnabled: any;
  selectedTaskTemplate: Task | null;
  changelog: ChangeLog | null;
  errorLoading: boolean;
};

// useBlocker must be called from its own component so it keeps a stable hook position
// regardless of how Formik invokes the surrounding render-prop function.
function TaskTemplateOverviewBlocker({ getBlockMessage }: { getBlockMessage: (pathname: string) => string | null }) {
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

// Discriminates what a pending "apply" fetcher submission should do once it settles - the
// three write paths this component drives (save/update, archive, restore) all PUT through the
// same `intent: "apply"` action (see AdminTasks.tsx/WorkspaceTasks.tsx), so the client side needs
// its own record of which one is in flight and what to do with the result.
type PendingApply =
  | {
      kind: "save";
      requestType: string;
      resetForm: () => void;
      setRequestError: (error: { title: string; subtitle: string } | null) => void;
      closeModal: () => void;
    }
  | { kind: "archive" }
  | { kind: "restore" };

export function TaskTemplateOverview({
  taskTemplates,
  editVerifiedTasksEnabled,
  selectedTaskTemplate,
  changelog,
  errorLoading,
}: TaskOverviewProps) {
  const [isSaving, setIsSaving] = React.useState(false);
  // Feature flags are now read via the root loader (Features/App/App.tsx), not a react-query
  // cache entry - queryClient.invalidateQueries(getFeatureFlags()) below would be a silent
  // no-op, so those three call sites revalidate() instead. Data reads (the selected task
  // template, its changelog) come from the parent route's loader as props now, rather than
  // useQuery, so a successful write revalidates the loader instead of a query cache.
  const revalidator = useRevalidator();
  const fetcher = useFetcher<
    | { ok: true; intent: "apply" | "applyYaml"; task: Task }
    | { ok: false; intent: "apply" | "applyYaml"; error: { title: string; message: string } }
  >();
  const pendingApplyRef = React.useRef<PendingApply | null>(null);
  const params = useParams();
  const navigate = useNavigate();

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "apply") {
      return;
    }
    const pending = pendingApplyRef.current;
    pendingApplyRef.current = null;
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
            subtitle={`Request to restore ${params.name} succeeded`}
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
          subtitle={`Request to update ${result.task.displayName} succeeded`}
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

  if (errorLoading || !selectedTaskTemplate || !changelog) {
    return (
      <EmptyState title="Task Template not found" message="Crikey. We can't find the template you are looking for." />
    );
  }

  const canEdit = !selectedTaskTemplate?.verified || (editVerifiedTasksEnabled && selectedTaskTemplate?.verified);
  const isActive = selectedTaskTemplate.status === TaskTemplateStatus.Active;
  // params.version is a string, changelog.length is a number
  const isOldVersion = Boolean(params.version < changelog.length);
  const fieldKeys = selectedTaskTemplate.spec.params?.map((input: DataDrivenInput) => input.name) ?? [];
  const resultKeys = selectedTaskTemplate.spec.results?.map((input: DataDrivenInput) => input.name) ?? [];

  const reorder = (list, startIndex, endIndex) => {
    const result = Array.from(list);
    const [removed] = result.splice(startIndex, 1);
    result.splice(endIndex, 0, removed);
    return result;
  };

  const handleSaveTaskTemplate = (values, resetForm, requestType, setRequestError, closeModal) => {
    setIsSaving(true);
    let newVersion = requestType === TemplateRequestType.Overwrite ? selectedTaskTemplate.version : changelog.length + 1;
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

    const replace = requestType === TemplateRequestType.Overwrite;
    pendingApplyRef.current = { kind: "save", requestType, resetForm, setRequestError, closeModal };
    fetcher.submit(
      { intent: "apply", name: selectedTaskTemplate.name, replace: String(replace), body: JSON.stringify(body) },
      { method: "post" },
    );
  };

  const handleArchiveTaskTemplate = () => {
    pendingApplyRef.current = { kind: "archive" };
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
    pendingApplyRef.current = { kind: "restore" };
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
        // Same in-app "leave without saving" guard as before, ported from v5's <Prompt> to
        // v6/v7's useBlocker (requires the data router set up in Root.tsx). Returns the
        // confirm-dialog message to show for a given target pathname, or null to navigate
        // through unprompted.
        function getBlockMessage(pathname: string) {
          const templateMatch = matchPath({ path: AppPath.TaskTemplateDetail }, pathname);
          if (isDirty && !pathname.includes(templateMatch?.params?.id) && !isSubmitting) {
            return "Are you sure you want to leave? You have unsaved changes.";
          }
          if (isDirty && templateMatch?.params?.version !== selectedTaskTemplate.currentVersion && !isSubmitting) {
            return "Are you sure you want to change the version? Your changes will be lost.";
          }
          return null;
        }

        return (
          <div className={styles.container}>
            <Helmet>
              <title>{`Task manager - ${selectedTaskTemplate.displayName}`}</title>
            </Helmet>
            <TaskTemplateOverviewBlocker getBlockMessage={getBlockMessage} />
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
