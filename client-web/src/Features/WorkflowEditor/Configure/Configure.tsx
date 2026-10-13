import React from "react";
import { Tag, MultiSelect, InlineNotification, Button, Dropdown } from "@carbon/react";
import { Launch, Popup, TrashCan, Add } from "@carbon/react/icons";
import {
  ComposedModal,
  TextArea,
  TextInput,
  Toggle,
  TooltipHover,
  CheckboxList,
} from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import cx from "classnames";
import { useFeature } from "flagged";
import { Formik, FormikErrors, FormikProps, FieldArray } from "formik";
import capitalize from "lodash/capitalize";
import { Helmet } from "react-helmet";
import { Navigate, Route, Routes, useLocation, useParams } from "react-router-dom";
import * as Yup from "yup";
import TokenSection from "Components/TokenSection";
import { useEditorContext, useWorkspaceContext } from "Hooks";
import BasicSlider from "Components/Slider";
import { TokenActorKind, TokenType } from "Constants";
import { appLink, FeatureFlag } from "Config/appConfig";
import { WorkflowCanvas, ConfigureWorkflowFormValues, FlowWorkspace, WorkflowTriggerCondition } from "Types";
import { useEditorRouteData } from "../editorRouteData";
import BuildWebhookModalContent from "./BuildWebhookModalContent";
import ConfigureArtifacts from "./ConfigureArtifacts";
import ConfigureEventTrigger from "./ConfigureEventTrigger";
import CustomLabel from "./CustomLabel";
import NavPanel from "./SideNav";
import styles from "./configure.module.scss";

// Formik types a FieldArray item's error as `string | FormikErrors<Item>` (the string
// case covers a validation message on the item as a whole); narrow to the field-level
// message we actually render.
function getConditionFieldError<K extends keyof WorkflowTriggerCondition>(
  error: string | FormikErrors<WorkflowTriggerCondition> | undefined,
  key: K,
): string | undefined {
  if (error && typeof error === "object") {
    return error[key] as string | undefined;
  }
  return undefined;
}

const TRIGGER_YUP_SCHEMA = Yup.object().shape({
  enabled: Yup.bool(),
  conditions: Yup.array().of(
    Yup.object().shape({
      operation: Yup.string().required("Operation is required"),
      field: Yup.string().required("Field is required"),
      value: Yup.string().test({
        message: "Value is required",
        test: function (value) {
          const { values } = this.parent;
          if (!values) return value != null;
          return true;
        },
      }),
      values: Yup.array().of(Yup.string()).optional(),
    }),
  ),
});

interface ConfigureContainerProps {
  workflow: WorkflowCanvas;
  settingsRef: React.MutableRefObject<FormikProps<any> | null>;
}

function ConfigureContainer({ workflow, settingsRef }: ConfigureContainerProps) {
  const { workspace } = useWorkspaceContext();
  const rawParams = useParams<{ workspace: string; workflow: string }>();
  const params = { workspace: rawParams.workspace ?? "", workflow: rawParams.workflow ?? "" };
  const workflowTriggersEnabled = useFeature(FeatureFlag.WorkflowTriggersEnabled);
  const location = useLocation();
  const { workflowsQueryData } = useEditorContext();

  /*
   * The GitHub installation lookup moved to the editor route's loader (editorRoute.ts). It is
   * read through useMatches() rather than useLoaderData() because this component renders inside
   * Editor.tsx's descendant <Routes> - see editorRouteData.ts. `null` when the integration is not
   * installed or the lookup failed, which is the same falsy value the previous query's
   * `undefined` produced: the "Integration Required" notification below covers both.
   */
  const githubAppInstallation = useEditorRouteData()?.githubAppInstallation ?? null;

  const isOnConfigurePath = location.pathname.startsWith(
    appLink.editorConfigure({ workspace: params.workspace, workflow: params.workflow }),
  );

  const workflows = workflowsQueryData.content;
  const existingWorkflowNames = workflows?.map((workflow) => workflow.name) ?? [];
  const workspaceQuotasEnabled = useFeature(FeatureFlag.WorkspaceQuotasEnabled);

  return (
    <>
      <Helmet>
        <title>{`Configure - ${workflow.name}`}</title>
      </Helmet>
      <Formik<ConfigureWorkflowFormValues>
        innerRef={settingsRef}
        enableReinitialize
        onSubmit={() => void 0}
        initialValues={{
          description: workflow.description ?? "",
          // Absent on the workflow means no shared storage for its runs.
          workspace: {
            enabled: Boolean(workflow.workspace),
            size: workflow.workspace?.size ?? 1,
            mountPath: workflow.workspace?.mountPath ?? "",
          },
          icon: workflow.icon ?? "",
          name: workflow.name ?? "",
          displayName: workflow.displayName ?? "",
          timeout: workflow.timeout ?? null,
          retries: workflow.retries ?? null,
          labels: workflow.labels ? Object.entries(workflow.labels).map(([key, value]) => ({ key, value })) : [],
          triggers: workflow.triggers ?? {},
          config: workflow.config ?? [],
        }}
        validationSchema={Yup.object().shape({
          description: Yup.string().max(250, "Description must not be greater than 250 characters"),
          enableACCIntegration: Yup.boolean(),
          workspace: Yup.object().shape({
            enabled: Yup.boolean(),
            size: Yup.number().when("enabled", {
              is: true,
              then: (schema) => {
                const sized = schema.required("Enter the workspace size").min(1, "Size must be at least 1 GB");
                return workspaceQuotasEnabled
                  ? sized.max(
                      workspace.quotas.maxWorkspaceStorage,
                      `Size must not exceed quota of ${workspace.quotas.maxWorkspaceStorage} GB`,
                    )
                  : sized;
              },
            }),
            mountPath: Yup.string().nullable(),
          }),
          icon: Yup.string(),
          name: Yup.string()
            .required("Name is required")
            .max(100, "Enter a name that is at most 100 characters in length")
            .test("regex", `Name must only contain letters, numbers, and dashes`, (value) => {
              const regex = /^[a-zA-Z0-9\-]+$/;
              if (value) {
                return regex.test(value);
              }
              return true;
            })
            .notOneOf(
              existingWorkflowNames,
              `There’s already a workflow with that name in this workspace. Names must be unique.`,
            ),
          displayName: Yup.string().optional(),
          retries: Yup.number().min(0),
          timeout: workspaceQuotasEnabled
            ? Yup.number()
                .min(0)
                .max(
                  workspace.quotas.maxWorkflowRunDuration,
                  `Timeout must not exceed quota of ${workspace.quotas.maxWorkflowRunDuration} minutes`,
                )
            : Yup.number().min(0),
          triggers: Yup.object().shape({
            schedule: TRIGGER_YUP_SCHEMA,
            event: TRIGGER_YUP_SCHEMA,
            manual: TRIGGER_YUP_SCHEMA,
            webhook: TRIGGER_YUP_SCHEMA,
            github: TRIGGER_YUP_SCHEMA,
          }),
        })}
      >
        {(formikProps) =>
          isOnConfigurePath ? (
            <div className={styles.container}>
              <NavPanel workspace={params.workspace} workflowRef={params.workflow}></NavPanel>
              <Configure
                workflowTriggersEnabled={workflowTriggersEnabled as boolean}
                formikProps={formikProps}
                workflow={workflow}
                githubAppInstallation={githubAppInstallation}
                workspace={workspace}
              />
            </div>
          ) : null
        }
      </Formik>
    </>
  );
}

export default ConfigureContainer;

interface ConfigureProps {
  workflowTriggersEnabled: boolean;
  formikProps: FormikProps<ConfigureWorkflowFormValues>;
  workflow: WorkflowCanvas;
  githubAppInstallation: any;
  workspace: FlowWorkspace;
}

function Configure(props: ConfigureProps) {
  const workflowTokensEnabled = useFeature(FeatureFlag.WorkflowTokensEnabled);
  const integrationsEnabled = useFeature(FeatureFlag.IntegrationsEnabled);
  const workspaceQuotasEnabled = useFeature(FeatureFlag.WorkspaceQuotasEnabled);
  // Read off the URL rather than built from props.workflow.name: the two differ (appLink.editorCanvas
  // builds from the name, while specs and older bookmarks address the editor by id - see
  // editorRoute.ts), and the default-panel redirect below must not change which workflow the URL
  // identifies.
  const routeParams = useParams<{ workspace: string; workflow: string }>();
  const handleOnToggleChange = (value: any, id: string) => {
    props.formikProps.setFieldValue(id, value);
  };

  const {
    formikProps: { errors, handleBlur, touched, values, setFieldValue },
  } = props;

  const githubEvents = props.githubAppInstallation?.events
    ? props.githubAppInstallation.events.map((item: string) => ({
        labelText: item,
        id: item,
      }))
    : [];

  const findConditionIndex = (field: string): number => {
    const index = values.triggers.github.conditions.findIndex((condition) => condition.field === field);
    return index !== -1 ? index : values.triggers.github.conditions.length;
  };

  return (
    <div aria-label="Configure" className={styles.wrapper} role="region">
      <Routes>
        <Route
          path="configure/general"
          element={
            <>
          <Section title="Basic Information" description="The bare necessities - you gotta fill out all these fields">
            <TextInput
              id="name"
              label="Name"
              helperText="This is your unique identifier name within the Workspace. Can only contain letters, numbers, and dashes."
              placeholder="Name"
              value={values.name}
              onBlur={handleBlur}
              onChange={(e) => props.formikProps.handleChange(e)}
              invalid={Boolean(errors.name && touched.name)}
              invalidText={errors.name}
            />
            <TextInput
              id="displayName"
              label="Display Name (optional)"
              helperText="This is the name that will be displayed in the UI. If left empty, will be set to the unique name."
              placeholder="My Fantastical Workflow"
              value={values.displayName}
              onBlur={handleBlur}
              onChange={(e) => props.formikProps.handleChange(e)}
              invalid={Boolean(errors.displayName && touched.displayName)}
              invalidText={errors.displayName}
            />
            <div className={styles.descriptionContainer}>
              <p className={styles.descriptionLength}> {`${values.description.length} / 250`}</p>
              <TextArea
                id="description"
                labelText="Description (optional)"
                placeholder="Description"
                onBlur={handleBlur}
                onChange={(e) => props.formikProps.handleChange(e)}
                invalid={Boolean(errors.description && touched.description)}
                invalidText={errors.description}
                style={{ resize: "none" }}
                value={values.description}
              />
            </div>
            <h2 className={styles.iconTitle}>Pick an icon (any icon)</h2>
            <div className={styles.icons}>
              {workflowIcons.map(({ name, Icon }, index) => (
                <TooltipHover key={name} direction="top" tooltipText={capitalize(name)}>
                  <label
                    className={cx(styles.icon, {
                      [styles.active]: values.icon === name,
                    })}
                    key={`icon-number-${index}`}
                    title={capitalize(name)}
                  >
                    <input
                      id="icon"
                      readOnly
                      checked={values.icon === name}
                      onClick={(e) => props.formikProps.handleChange(e)}
                      value={name}
                      type="radio"
                    />
                    <Icon key={`${name}-${index}`} aria-label={`${name} icon`} />
                  </label>
                </TooltipHover>
              ))}
            </div>
          </Section>
          <Section title="Labels">
            <p className={styles.sectionDescription}>
              Create labels that can be used to query for specific Workflows, used at execution time, and can be useful
              in debugging the Workflow.
              <a
                aria-describedby="new-window-aria-desc-0"
                className={styles.link}
                href={appLink.docsWorkflowEditor()}
                target="_blank"
                rel="noopener noreferrer"
                data-testid="docs-link"
              >
                For more details, check our Docs
              </a>
            </p>
            <div className={styles.labelsContainer}>
              <div className={styles.tagsContainer}>
                <FieldArray
                  name="labels"
                  render={(arrayHelpers) =>
                    values.labels.map((label, index) => {
                      return (
                        <CustomLabel
                          formikPropsSetFieldValue={setFieldValue}
                          isEdit
                          editTrigger={({ openModal }: { openModal: () => void }) => (
                            <Tag
                              type="teal"
                              key={index}
                              filter
                              onClick={openModal}
                              onClose={() => arrayHelpers.remove(index)}
                            >
                              {`${label.key}=${label.value}`}
                            </Tag>
                          )}
                          labels={values.labels}
                          selectedLabel={{ ...label, index }}
                        />
                      );
                    })
                  }
                />
              </div>
              <CustomLabel formikPropsSetFieldValue={setFieldValue} labels={values.labels} />
            </div>
          </Section>
            </>
          }
        />
        <Route
          path="configure/triggers"
          element={
            <>
          {props.workflowTriggersEnabled && (
            <>
              <Section
                title="Manual"
                description="Enable workflow to be executed manually through the UI and triggered via the API."
              >
                <div className={styles.toggleContainer}>
                  <Toggle
                    reversed
                    id="triggers.manual.enabled"
                    data-testid="triggers.manual.enabled"
                    label="Enable"
                    onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.manual.enabled")}
                    toggled={values.triggers.manual.enabled}
                  />
                </div>
              </Section>
              <Section title="Scheduler" description="Enable workflow to be executed by a schedule.">
                <div className={styles.toggleContainer} style={{ marginTop: "1rem" }}>
                  <Toggle
                    reversed
                    id="triggers.schedule.enabled"
                    data-testid="triggers.scheduler.enabled"
                    label="Enable"
                    onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.schedule.enabled")}
                    toggled={values.triggers.schedule.enabled}
                  />
                </div>
                {values.triggers.schedule.enabled ? (
                  <InlineNotification
                    lowContrast
                    kind="info"
                    title="All enabled schedules"
                    subtitle="will execute. Manage them in the Schedule tab."
                    style={{ marginTop: "1rem" }}
                    hideCloseButton
                  />
                ) : (
                  <InlineNotification
                    lowContrast
                    kind="warning"
                    title="No schedules"
                    subtitle="will execute, regardless of status. Manage them in the Schedule tab."
                    style={{ marginTop: "1rem" }}
                    hideCloseButton
                  />
                )}
              </Section>
              <Section title="Webhook" description="Listen for events and receive data from an external system.">
                <div className={styles.triggerSection}>
                  <div className={styles.toggleContainer}>
                    <Toggle
                      id="triggers.webhook.enabled"
                      label="Enable"
                      toggled={values.triggers.webhook.enabled}
                      onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.webhook.enabled")}
                      tooltipContent="Enable workflow to be executed by the webhook API"
                      tooltipProps={{ direction: "top" }}
                      reversed
                    />
                  </div>
                  {values.triggers.webhook.enabled && (
                    <ComposedModal
                      modalHeaderProps={{
                        title: "Webhook Usage",
                      }}
                      composedModalProps={{
                        containerClassName: styles.buildWebhookContainer,
                        shouldCloseOnOverlayClick: true,
                      }}
                      modalTrigger={({ openModal }) => (
                        <Button kind="ghost" onClick={openModal} renderIcon={Popup} size="sm">
                          <p>View usage example</p>
                        </Button>
                      )}
                    >
                      {({ closeModal }) => (
                        <BuildWebhookModalContent closeModal={closeModal} workflowRef={props.workflow.name} />
                      )}
                    </ComposedModal>
                  )}
                </div>
              </Section>
              <Section title="Events">
                <p className={styles.sectionDescription}>
                  Listen for events received in the CloudEvent format and trigger this Workflow based on filter
                  matching. Learn more about
                  <a
                    aria-describedby="new-window-aria-desc-0"
                    className={styles.link}
                    href={appLink.docsWorkflowEditor()}
                    target="_blank"
                    rel="noopener noreferrer"
                    data-testid="docs-link"
                  >
                    Event Triggers <Launch />
                  </a>
                  .
                </p>
                <div className={styles.triggerSection}>
                  <div className={styles.toggleContainer}>
                    <Toggle
                      id="triggers.event.enabled"
                      label="Enable"
                      toggled={values.triggers.event.enabled}
                      onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.event.enabled")}
                      tooltipContent="Enable workflow to be triggered by received events."
                      tooltipProps={{ direction: "top" }}
                      reversed
                    />
                  </div>
                  {values.triggers.event.enabled && (
                    <>
                      <p className={styles.sectionDescription}>
                        The following filters will be applied to any incoming event based on the{" "}
                        <a
                          aria-describedby="new-window-aria-desc-0"
                          className={styles.link}
                          href="cloudevents.io"
                          target="_blank"
                          rel="noopener noreferrer"
                          data-testid="docs-link"
                        >
                          CloudEvent <Launch />
                        </a>{" "}
                        specification. Configure using exact match or regular expression to filter the events.
                      </p>
                      <FieldArray name="triggers.event.conditions">
                        {({ remove, push }) => (
                          <>
                            <div>
                              {values.triggers.event.conditions.map((condition, idx) => {
                                console.log(errors?.triggers?.event?.conditions);
                                return (
                                  <div
                                    style={
                                      idx > 0
                                        ? { display: "flex", gap: "0.5rem", width: "50rem" }
                                        : { display: "flex", gap: "0.5rem", width: "50rem", alignItems: "baseline" }
                                    }
                                  >
                                    <TextInput
                                      id={`triggers.event.conditions[${idx}].field`}
                                      name={`triggers.event.conditions[${idx}].field`}
                                      label="Field"
                                      invalid={Boolean(getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "field"))}
                                      invalidText={getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "field")}
                                      placeholder="type"
                                      //helperText="A regular expression."
                                      value={condition.field}
                                      onBlur={handleBlur}
                                      onChange={(e) => props.formikProps.handleChange(e)}
                                      hideLabel={idx > 0}
                                    />
                                    <Dropdown
                                      id={`triggers.event.conditions[${idx}].operation`}
                                      type="default"
                                      label="Operation"
                                      light={false}
                                      items={["in", "matches", "equals"]}
                                      selectedItem={condition.operation}
                                      onChange={(e) => {
                                        props.formikProps.setFieldValue(
                                          `triggers.event.conditions[${idx}].operation`,
                                          e.selectedItem,
                                        );
                                      }}
                                      invalid={Boolean(getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "operation"))}
                                      invalidText={getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "operation")}
                                      hideLabel={idx > 0}
                                      titleText="Operation"
                                    />
                                    <TextInput
                                      id={`triggers.event.conditions[${idx}].value`}
                                      name={`triggers.event.conditions[${idx}].value`}
                                      label="Value"
                                      invalid={Boolean(getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "value"))}
                                      invalidText={getConditionFieldError(errors.triggers?.event?.conditions?.[idx], "value")}
                                      placeholder="io.boomerang.[event|phase|status]"
                                      //helperText="A regular expression."
                                      value={condition.value}
                                      onBlur={handleBlur}
                                      onChange={(e) => props.formikProps.handleChange(e)}
                                      hideLabel={idx > 0}
                                    />
                                    <Button
                                      title="delete"
                                      kind="danger--ghost"
                                      size="sm"
                                      onClick={() => remove(idx)}
                                      renderIcon={TrashCan}
                                      iconDescription="Remove Filter"
                                      tooltipPosition={"right"}
                                      tooltipAlignment={"start"}
                                      style={
                                        idx > 0
                                          ? {
                                              inlineSize: "2.5rem",
                                              minBlockSize: "2.5rem",
                                              height: "2.5rem",
                                              width: "2.5rem",
                                              alignItems: "center",
                                              padding: 0,
                                            }
                                          : {
                                              inlineSize: "2.5rem",
                                              minBlockSize: "2.5rem",
                                              height: "2.5rem",
                                              width: "2.5rem",
                                              alignItems: "center",
                                              padding: 0,
                                              marginTop: "28px",
                                            }
                                      }
                                      hasIconOnly
                                    />
                                  </div>
                                );
                              })}
                            </div>
                            <Button
                              kind="ghost"
                              onClick={() => push({ field: "", operation: "", value: "" })}
                              renderIcon={Add}
                              size="sm"
                              style={{ marginRight: "0.5rem" }}
                            >
                              Add row
                            </Button>
                          </>
                        )}
                      </FieldArray>
                      {/* <div className={styles.webhookContainer}> */}
                      <ComposedModal
                        modalHeaderProps={{
                          title: "Event Trigger Usage",
                        }}
                        composedModalProps={{
                          containerClassName: styles.buildWebhookContainer,
                          shouldCloseOnOverlayClick: true,
                        }}
                        modalTrigger={({ openModal }) => (
                          <Button kind="ghost" onClick={openModal} renderIcon={Popup} size="sm">
                            <p>View usage example</p>
                          </Button>
                        )}
                      >
                        {({ closeModal }) => (
                          <ConfigureEventTrigger closeModal={closeModal} workflowRef={props.workflow.name} />
                        )}
                      </ComposedModal>
                      {/* </div> */}
                    </>
                  )}
                </div>
              </Section>
              {integrationsEnabled && (
                <Section title="GitHub" description="" beta>
                  <p className={styles.sectionDescription}>
                    Listen for and respond to events from GitHub. Filter the events and repositories that will trigger
                    this Workflow. The GitHub integration must be enabled for this Trigger to work. Learn more about
                    <a
                      aria-describedby="new-window-aria-desc-0"
                      className={styles.link}
                      href="https://docs.github.com/en/webhooks/webhook-events-and-payloads"
                      target="_blank"
                      rel="noopener noreferrer"
                      data-testid="docs-link"
                    >
                      GitHub Events <Launch />
                    </a>
                    .
                  </p>
                  <div className={styles.toggleContainer}>
                    <Toggle
                      id="triggers.github.enabled"
                      label="Enable"
                      toggled={values.triggers.github.enabled}
                      onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.github.enabled")}
                      reversed
                      disabled={!props.githubAppInstallation}
                    />
                  </div>
                  {!props.githubAppInstallation && (
                    <InlineNotification
                      lowContrast
                      kind="warning"
                      title="Integration Required"
                      subtitle="The GitHub integration is required for this to be enabled."
                      style={{ marginTop: "1rem" }}
                      hideCloseButton
                    />
                  )}
                  {values.triggers.github.enabled && props.githubAppInstallation && (
                    <div className={styles.githubTriggerContainer}>
                      <h2 className={styles.iconTitle}>Repository Filter</h2>
                      <div style={{ maxWidth: "27.125rem" }}>
                        <MultiSelect
                          hideLabel
                          id="triggers.github.repositories"
                          label="Choose Repositories"
                          invalid={false}
                          onChange={({ selectedItems }: { selectedItems: string[] | null }) => {
                            const fieldIdx = findConditionIndex("repositories");
                            const value = { operation: "in", field: "repositories", values: selectedItems ?? [] };
                            props.formikProps.setFieldValue(`triggers.github.conditions[${fieldIdx}]`, value);
                          }}
                          items={props.githubAppInstallation?.repositories}
                          itemToString={(repository: string) => {
                            return props.githubAppInstallation.orgSlug + " / " + repository;
                          }}
                          initialSelectedItems={
                            values.triggers.github.conditions.find((condition) => condition.field === "repositories")
                              ?.values
                          }
                          titleText="Filter by Repository"
                        />
                      </div>
                      <h2 className={styles.iconTitle}>Events Filter</h2>
                      <CheckboxList
                        id="triggers.github.events"
                        initialSelectedItems={
                          values.triggers.github.conditions.find((condition) => condition.field === "events")?.values
                        }
                        labelText="Select events that you wish to trigger this Workflow"
                        onChange={(_, __, ____, checked) => {
                          const fieldIdx = findConditionIndex("events");
                          const value = { operation: "in", field: "events", values: checked };
                          props.formikProps.setFieldValue(`triggers.github.conditions[${fieldIdx}]`, value);
                        }}
                        options={githubEvents}
                      />
                    </div>
                  )}
                </Section>
              )}
            </>
          )}
            </>
          }
        />
        {/* <Route path="configure/parameters"> */}
        {/* <Section title="GitHub" description="Auto inject GitHub Parameters." beta>
            <div className={styles.toggleContainer}>
              <Toggle
                id="bob"
                label="Enable"
                toggled={values.triggers.webhook.enabled}
                onToggle={(checked: boolean) => handleOnToggleChange(checked, "triggers.webhook.enabled")}
                reversed
                disabled
              />
            </div>
          </Section> */}
        {/* </Route> */}
        <Route
          path="configure/run"
          element={
            <>
          <Section title="Execution" description="Customize how your Workflow behaves.">
            <div>
              <div className={styles.runOptionsSection}>
                <TextInput
                  id="timeout"
                  label="Timeout"
                  helperText={
                    workspaceQuotasEnabled
                      ? `In minutes. Maximum defined by your Workspace quota is ${props.workspace.quotas.maxWorkflowRunDuration} minutes.`
                      : "In minutes."
                  }
                  value={values.timeout ?? undefined}
                  onBlur={handleBlur}
                  onChange={(e) => props.formikProps.handleChange(e)}
                  invalid={Boolean(errors.timeout && touched.timeout)}
                  invalidText={errors.timeout}
                  type="number"
                />
                <TextInput
                  id="retries"
                  label="Retries"
                  helperText="The number of times to retry a failed workflow. Defaults to 0."
                  value={values.retries ?? undefined}
                  onBlur={handleBlur}
                  onChange={(e) => props.formikProps.handleChange(e)}
                  invalid={Boolean(errors.retries && touched.retries)}
                  invalidText={errors.retries}
                />
              </div>
            </div>
          </Section>
            </>
          }
        />
        <Route
          path="configure/workspace"
          element={
            <>
          <Section
            title="Workspace"
            description="A shared filesystem every task in a run can read and write. It is created when the run starts and removed when the run completes. To keep files between runs, use Upload Artifact and Download Artifact with scope Workflow."
          >
            {props.workflow.legacyWorkflowStorage ? (
              <InlineNotification
                lowContrast
                kind="warning"
                title="Workflow storage was removed in Flow 5"
                subtitle="This workflow can't run until it is saved again. Keep files between runs with Upload Artifact and Download Artifact with scope Workflow."
                style={{ marginBottom: "1rem" }}
                hideCloseButton
              />
            ) : null}
            <div className={styles.storageToggle}>
              <div className={styles.toggleContainer}>
                <Toggle
                  id="workspace.enabled"
                  data-testid="workspace.enabled"
                  label="Shared workspace for each run"
                  toggled={values.workspace.enabled}
                  onToggle={(checked: boolean) => handleOnToggleChange(checked, "workspace.enabled")}
                  reversed
                />
              </div>
              {values.workspace.enabled && (
                <div className={styles.workspaceFields}>
                  <BasicSlider
                    id="workspace.size"
                    data-testid="workspace.size"
                    min={1}
                    // With quotas off there is no ceiling; the slider still needs one.
                    max={
                      workspaceQuotasEnabled
                        ? props.workspace.quotas.maxWorkspaceStorage
                        : Math.max(values.workspace.size, 100)
                    }
                    inputType="text"
                    labelText="Size"
                    helperText={
                      workspaceQuotasEnabled
                        ? `Size in gigabytes. Maximum defined by your Workspace quota is ${props.workspace.quotas.maxWorkspaceStorage} GB.`
                        : "Size in gigabytes"
                    }
                    sliderValue={values.workspace.size}
                    onChange={(value: number) => setFieldValue("workspace.size", value)}
                  />
                  {errors.workspace?.size ? <p className={styles.fieldError}>{errors.workspace.size}</p> : null}
                  <TextInput
                    id="workspace.mountPath"
                    data-testid="workspace.mountPath"
                    label="Mount path"
                    helperText="Where every task sees the workspace"
                    placeholder="/workspace"
                    value={values.workspace.mountPath}
                    onBlur={handleBlur}
                    onChange={(e) => props.formikProps.handleChange(e)}
                    invalid={Boolean(errors.workspace?.mountPath && touched.workspace?.mountPath)}
                    invalidText={errors.workspace?.mountPath}
                  />
                </div>
              )}
            </div>
          </Section>
            </>
          }
        />
        <Route
          path="configure/artifacts"
          element={
            <>
          <Section
            title="Artifacts"
            description="Files this workflow keeps between runs. An Upload Artifact task with scope Workflow writes one; a Download Artifact task with scope Workflow reads it in a later run. Each name keeps its newest verified upload until it is replaced or deleted."
          >
            <ConfigureArtifacts workspace={routeParams.workspace ?? ""} />
          </Section>
            </>
          }
        />
        <Route
          path="configure/tokens"
          element={
            <>
          <Section
            title="Tokens"
            description="
                  Workflow tokens allow other applications to access the APIs as if they were this Workflow. Be careful how you
                  distribute these tokens!"
          >
            {!workflowTokensEnabled && (
              <InlineNotification
                lowContrast
                kind="warning"
                title="Feature Required"
                subtitle="Workflow Tokens require a feature to be enabled at the platform."
                style={{ marginTop: "1rem" }}
                hideCloseButton
              />
            )}
            <div>
              <dl className={styles.detailedListContainer}>
                <TokenSection type={TokenType.Key} actorKind={TokenActorKind.Workflow} principal={props.workflow.name} />
              </dl>
            </div>
          </Section>
            </>
          }
        />
        {/*
          * `appLink.*` (a builder that takes the params), never `AppPath.*` (the route PATTERN):
          * v5's <Redirect from to> interpolated ":workspace"/":workflow" from the current match,
          * v7's <Navigate> does not, so an AppPath here navigates to the literal
          * "/:workspace/editor/:workflow/..." URL and the editor loader fetches a workflow by
          * that name. The editor header links to the bare `configure` path, so this redirect is
          * the entry point to the whole Configure tab.
          */}
        <Route
          path="configure"
          element={
            <Navigate
              to={appLink.editorConfigureGeneral({
                workspace: routeParams.workspace ?? "",
                workflow: routeParams.workflow ?? "",
              })}
              replace
            />
          }
        />
      </Routes>
    </div>
  );
}

interface SectionProps {
  title: string;
  description?: string;
  beta?: boolean;
  children: React.ReactNode;
  editModal?: React.ReactNode;
}

function Section({ children, description, editModal, title, beta }: SectionProps) {
  return (
    <section className={styles.sectionContainer}>
      <div className={styles.sectionHeader}>
        <h1 className={styles.sectionTitle}>
          {title} {beta ? <Tag>beta</Tag> : null}
        </h1>
        {editModal}
      </div>
      {description ? <p className={styles.sectionDescription}>{description}</p> : null}
      {children}
    </section>
  );
}
