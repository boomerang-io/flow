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
import { Formik, FormikProps, FieldArray } from "formik";
import capitalize from "lodash/capitalize";
import { Helmet } from "react-helmet";
import { useQuery } from "react-query";
import { Switch, Route, Redirect, useLocation, useParams } from "react-router-dom";
import * as Yup from "yup";
import TokenSection from "Components/TokenSection";
import { useEditorContext, useTeamContext } from "Hooks";
import { WorkspaceConfigType } from "Constants";
import { appLink, AppPath, FeatureFlag } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { Workflow, ConfigureWorkflowFormValues, FlowTeam } from "Types";
import BuildWebhookModalContent from "./BuildWebhookModalContent";
import ConfigureEventTrigger from "./ConfigureEventTrigger";
import ConfigureStorage from "./ConfigureStorage";
import CustomLabel from "./CustomLabel";
import NavPanel from "./SideNav";
import styles from "./configure.module.scss";

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
  workflow: Workflow;
  settingsRef: React.MutableRefObject<FormikProps<any> | null>;
}

function ConfigureContainer({ workflow, settingsRef }: ConfigureContainerProps) {
  const { team } = useTeamContext();
  const params = useParams<{ team: string; workflow: string }>();
  const workflowTriggersEnabled = useFeature(FeatureFlag.WorkflowTriggersEnabled);
  const location = useLocation();
  const { workflowsQueryData } = useEditorContext();

  const getGitHubAppInstallationForTeam = serviceUrl.getGitHubAppInstallationForTeam({
    team: params.team,
  });

  const getGitHubInstallationQuery = useQuery({
    queryKey: getGitHubAppInstallationForTeam,
    queryFn: resolver.query(getGitHubAppInstallationForTeam),
    enabled: Boolean(params.team),
  });

  const isOnConfigurePath = location.pathname.startsWith(
    appLink.editorConfigure({ team: params.team, workflow: params.workflow }),
  );

  // Find the specific workspace configs we want that are used for storage storage
  const workflowStorageConfig = workflow.workspaces?.find(
    (workspaceConfig) => workspaceConfig.type === WorkspaceConfigType.Workflow,
  );
  const workflowRunStorageConfig = workflow.workspaces?.find(
    (workspaceConfig) => workspaceConfig.type === WorkspaceConfigType.WorflowRun,
  );

  const workflows = workflowsQueryData.content;
  const existingWorkflowNames = workflows?.map((workflow) => workflow.name) ?? [];

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
          storage: {
            workflow: {
              enabled: Boolean(workflowStorageConfig),
              size: workflowStorageConfig?.spec?.size ?? 1,
              mountPath: workflowStorageConfig?.spec?.mountPath ?? "",
            },
            workflowrun: {
              enabled: Boolean(workflowRunStorageConfig),
              size: workflowRunStorageConfig?.spec?.size ?? 1,
              mountPath: workflowRunStorageConfig?.spec?.mountPath ?? "",
            },
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
          storage: Yup.object().shape({
            activity: Yup.object().shape({
              enabled: Yup.boolean().nullable(),
              size: Yup.number().required("Enter the storage size"),
              mountPath: Yup.string().nullable(),
            }),
            workflow: Yup.object().shape({
              enabled: Yup.boolean().nullable(),
              size: Yup.number().required("Enter the storage size"),
              mountPath: Yup.string().nullable(),
            }),
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
              `There’s already a workflow with that name in this team. Names must be unique.`,
            ),
          displayName: Yup.string().optional(),
          retries: Yup.number().min(0),
          timeout: Yup.number()
            .min(0)
            .max(
              team.quotas.maxWorkflowRunDuration,
              `Timeout must not exceed quota of ${team.quotas.maxWorkflowRunDuration} minutes`,
            ),
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
              <NavPanel team={params.team} workflowRef={params.workflow}></NavPanel>
              <Configure
                workflowTriggersEnabled={workflowTriggersEnabled as boolean}
                formikProps={formikProps}
                workflow={workflow}
                githubAppInstallation={getGitHubInstallationQuery.data}
                team={team}
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
  workflow: Workflow;
  githubAppInstallation: any;
  team: FlowTeam;
}

function Configure(props: ConfigureProps) {
  const workflowTokensEnabled = useFeature(FeatureFlag.WorkflowTokensEnabled);
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
      <Switch>
        <Route exact path={AppPath.EditorConfigureGeneral}>
          <Section title="Basic Information" description="The bare necessities - you gotta fill out all these fields">
            <TextInput
              id="name"
              label="Name"
              helperText="This is your unique identifier name within the Team. Can only contain letters, numbers, and dashes."
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
                    <Icon key={`${name}-${index}`} alt={`${name} icon`} />
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
                              selectedLabel={label}
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
        </Route>
        <Route exact path={AppPath.EditorConfigureTriggers}>
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
                                      invalid={Boolean(errors.triggers?.event?.conditions?.[idx]?.field)}
                                      invalidText={errors.triggers?.event?.conditions?.[idx]?.field}
                                      placeholder="type"
                                      //helperText="A regular expression."
                                      value={condition.field}
                                      onBlur={handleBlur}
                                      onChange={(e) => props.formikProps.handleChange(e)}
                                      hideLabel={idx > 0}
                                    />
                                    <Dropdown
                                      id={`triggers.event.conditions[${idx}].operation`}
                                      name={`triggers.event.conditions[${idx}].operation`}
                                      type="default"
                                      label="Operation"
                                      light={false}
                                      items={["in", "matches", "equals"]}
                                      value={condition.operation}
                                      onChange={(e) => {
                                        console.log(e);
                                        props.formikProps.setFieldValue(
                                          `triggers.event.conditions[${idx}].operation`,
                                          e.selectedItem,
                                        );
                                      }}
                                      invalid={Boolean(errors.triggers?.event?.conditions?.[idx]?.operation)}
                                      invalidText={errors.triggers?.event?.conditions?.[idx]?.operation}
                                      hideLabel={idx > 0}
                                      titleText="Operation"
                                    />
                                    <TextInput
                                      id={`triggers.event.conditions[${idx}].value`}
                                      name={`triggers.event.conditions[${idx}].value`}
                                      label="Value"
                                      invalid={Boolean(errors.triggers?.event?.conditions?.[idx]?.value)}
                                      invalidText={errors.triggers?.event?.conditions?.[idx]?.value}
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
                        onChange={({ selectedItems }: { selectedItems: Array<{ label: string; value: string }> }) => {
                          const fieldIdx = findConditionIndex("repositories");
                          const value = { operation: "in", field: "repositories", values: selectedItems };
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
            </>
          )}
        </Route>
        {/* <Route exact path={AppPath.EditorConfigureParams}> */}
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
        <Route exact path={AppPath.EditorConfigureRun}>
          <Section title="Execution" description="Customize how your Workflow behaves.">
            <div>
              <div className={styles.runOptionsSection}>
                <TextInput
                  id="timeout"
                  label="Timeout"
                  helperText={`In minutes. Maximum defined by your Team quota is ${props.team.quotas.maxWorkflowRunDuration} minutes.`}
                  value={values.timeout}
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
                  value={values.retries}
                  onBlur={handleBlur}
                  onChange={(e) => props.formikProps.handleChange(e)}
                  invalid={Boolean(errors.retries && touched.retries)}
                  invalidText={errors.retries}
                />
              </div>
            </div>
          </Section>
        </Route>
        <Route exact path={AppPath.EditorConfigureWorkspaces}>
          <Section
            title="Workspaces"
            description="Declare storage options to be used at execution time. This will be
                  limited by your teams Storage quotas which will error executions if you exceed the allowed maximum."
          >
            <div className={styles.storageToggle}>
              <div className={styles.toggleContainer}>
                <Toggle
                  id="enableWorkflowPersistentStorage"
                  label="Enable Workflow Storage"
                  toggled={values.storage?.workflow?.enabled}
                  onToggle={(checked: boolean) => handleOnToggleChange(checked, "storage.workflow.enabled")}
                  tooltipContent="Persist data across workflow runs (executions)"
                  tooltipProps={{ direction: "top" }}
                  reversed
                />
              </div>
              {values.storage?.workflow?.enabled && (
                <div className={styles.webhookContainer}>
                  <ComposedModal
                    modalHeaderProps={{
                      title: "Configure Workspace - Workflow Storage",
                      subtitle: (
                        <>
                          <p>
                            The Workflow storage is persisted across workflow runs (executions) and allows you to share
                            artifacts between workflows, such as maintaining a cache of files used every execution.
                          </p>
                          <p style={{ marginTop: "0.5rem" }}>
                            Note: use with caution as this can lead to a collision if you are running many executions in
                            parallel using the same artifact.
                          </p>
                        </>
                      ),
                    }}
                    composedModalProps={
                      {
                        // containerClassName: styles.buildWebhookContainer,
                        // shouldCloseOnOverlayClick: true,
                      }
                    }
                    modalTrigger={({ openModal }) => (
                      <button
                        className={styles.regenerateText}
                        style={{ marginBottom: "0.5rem" }}
                        type="button"
                        onClick={openModal}
                      >
                        <p>Configure</p>
                      </button>
                    )}
                  >
                    {({ closeModal }) => (
                      <ConfigureStorage
                        size={values.storage?.workflow?.size}
                        mountPath={values.storage?.workflow?.mountPath}
                        handleOnChange={(storageValues: any) => {
                          setFieldValue("storage.workflow", storageValues);
                        }}
                        closeModal={closeModal}
                        quota={props.team.quotas.maxWorkflowStorage}
                      />
                    )}
                  </ComposedModal>
                </div>
              )}
            </div>
            <div className={styles.storageToggle}>
              <div className={styles.toggleContainer}>
                <Toggle
                  id="enableRunPersistentStorage"
                  label="Enable Run Storage"
                  toggled={values.storage?.workflowrun?.enabled}
                  onToggle={(checked: boolean) => handleOnToggleChange(checked, "storage.workflowrun.enabled")}
                  tooltipContent="Persist workflow data per executions"
                  tooltipProps={{ direction: "top" }}
                  reversed
                />
              </div>
              {values.storage?.workflowrun?.enabled && (
                <div className={styles.webhookContainer}>
                  <ComposedModal
                    modalHeaderProps={{
                      title: "Configure Workflow - Run Storage",
                      subtitle: (
                        <>
                          <p>
                            The Run storage is persisted per workflow execution and allows you to share short-lived
                            artifacts between tasks in the workflow.
                          </p>
                          <p style={{ marginTop: "0.5rem" }}>
                            Note: All artifacts will be deleted at the end of the workflow execution. If you want to
                            persist long term use alternative Workspaces.
                          </p>
                        </>
                      ),
                    }}
                    modalTrigger={({ openModal }) => (
                      <button className={styles.regenerateText} type="button" onClick={openModal}>
                        <p>Configure</p>
                      </button>
                    )}
                  >
                    {({ closeModal }) => (
                      <ConfigureStorage
                        size={values.storage.workflowrun.size}
                        mountPath={values.storage.workflowrun.mountPath}
                        handleOnChange={(storageValues: any) => {
                          setFieldValue("storage.activity", storageValues);
                        }}
                        closeModal={closeModal}
                        quota={props.team.quotas.maxWorkflowRunStorage}
                        isActivity
                      />
                    )}
                  </ComposedModal>
                </div>
              )}
            </div>
          </Section>
        </Route>
        <Route exact path={AppPath.EditorConfigureTokens}>
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
                <TokenSection type="workflow" principal={props.workflow.name} />
              </dl>
            </div>
          </Section>
        </Route>
        <Redirect exact from={AppPath.EditorConfigure} to={AppPath.EditorConfigureGeneral} />
      </Switch>
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
