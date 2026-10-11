import React from "react";
import {
  Accordion,
  AccordionItem,
  Button,
  DatePicker,
  DatePickerInput,
  InlineNotification,
  ModalBody,
  ModalFooter,
  RadioButton,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListRow,
  StructuredListCell,
  StructuredListBody,
} from "@carbon/react";
import {
  Creatable,
  ComboBox,
  DynamicFormik,
  Loading,
  ModalForm,
  TextInput,
} from "@boomerang-io/carbon-addons-boomerang-react";
import cronstrue from "cronstrue";
import moment from "moment-timezone";
import * as Yup from "yup";
import { useWorkspaceContext } from "Hooks";
import {
  ALL_DAYS,
  DAYS_MONDAY_FIRST,
  WEEKDAYS,
  ScheduleFrequency,
  describeCron,
  frequencyToSchedule,
  nextOccurrence,
  scheduleFrequency,
  weeklyCron,
} from "Utils/cronHelper";
import { DATETIME_LOCAL_INPUT_FORMAT, defaultTimeZone, timezoneOptions, transformTimeZone } from "Utils/dateHelper";
import { validateCronExpression } from "Config/resourceRoutes";
import { DataDrivenInput, DayOfWeekKey, ScheduleManagerFormInputs, ScheduleUnion, Workflow } from "Types";
import TimeField from "./TimeField";
import styles from "./ScheduleManagerForm.module.scss";

interface CreateEditFormProps {
  // Called with the form values AND the modal's own closeModal. The parent
  // (ScheduleCreator/ScheduleEditor) submits a useFetcher() and stashes closeModal in a ref,
  // closing only from its fetcher-settle effect on success - the fetcher's result arrives by
  // re-render, not as an awaitable promise, so the old `await handleSubmit(); closeModal()`
  // contract cannot hold. Same shape as CreateWorkflow.tsx's handleImportWorkflow(workflow,
  // closeModal). On failure the modal stays open and `isError` renders the inline notification,
  // exactly as before.
  handleSubmit: (args: ScheduleManagerFormInputs, closeModal: () => void) => void;
  includeWorkflowDropdown?: boolean;
  isError: boolean;
  isLoading: boolean;
  modalProps: any;
  schedule?: ScheduleUnion;
  type: "create" | "edit";
  workflow?: Workflow;
  workflowOptions?: Array<Workflow>;
}

const FREQUENCIES: Array<{ value: ScheduleFrequency; label: string }> = [
  { value: "once", label: "Once" },
  { value: "hourly", label: "Hourly" },
  { value: "daily", label: "Daily" },
  { value: "weekdays", label: "Weekdays" },
  { value: "weekly", label: "Weekly" },
  { value: "custom", label: "Custom (cron)" },
];

const DATE_FORMAT = "MM/DD/YYYY";
const TIME_PATTERN = /^\d{2}:\d{2}$/;
const needsTime = (frequency: ScheduleFrequency) => ["daily", "weekdays", "weekly"].includes(frequency);

// Required parameters first, each labelled by its Label or else its name, with the description as helper text.
function toScheduleInputs(params: Workflow["params"]): Array<DataDrivenInput> {
  return [...(params ?? [])]
    .sort((a, b) => Number(Boolean(b.required)) - Number(Boolean(a.required)))
    .map((param) => ({
      ...param,
      key: `$parameter:${param.name}`,
      label: param.label || param.name,
      helperText: param.description,
      description: undefined,
      defaultValue: param.default,
    }));
}

export default function CreateEditForm(props: CreateEditFormProps) {
  const { workspace } = useWorkspaceContext();
  const [workflowParams, setWorkflowParams] = React.useState<Array<DataDrivenInput>>(
    toScheduleInputs(props.workflow?.params),
  );

  const when = props.schedule ? scheduleFrequency(props.schedule as any) : scheduleFrequency({ type: "runOnce" });
  let initFormValues: Partial<ScheduleManagerFormInputs> = {
    id: props.schedule?.id,
    name: props.schedule?.name ?? "",
    dateTime: "",
    description: props.schedule?.description ?? "",
    frequency: props.type === "edit" ? when.frequency : "once",
    days: when.days,
    time: when.time,
    minute: when.minute,
    timezone: transformTimeZone(defaultTimeZone),
    workflow: props.workflow,
    labels: [],
  };

  /**
   * Default values if they exist. Has to be before the values from the saved schedule
   */
  workflowParams.forEach((param) => {
    if (param.name) {
      initFormValues[`$parameter:${param.name}`] = param.default;
    }
  });

  /**
   * Namespace parameter values if they exist from saved schedule
   */
  const scheduleParams = props.schedule?.params;
  if (Array.isArray(scheduleParams) && scheduleParams.length > 0) {
    for (const param of scheduleParams) {
      initFormValues[`$parameter:${param["name"]}`] = param["value"];
    }
  }

  /**
   * Handle creating it from calendar click
   */
  if (props.type === "create" && props.schedule && props.schedule.type === "runOnce") {
    initFormValues["dateTime"] = moment(props.schedule.dateSchedule).format(DATETIME_LOCAL_INPUT_FORMAT);
  }

  if (props.type === "edit" && props.schedule) {
    const timeZoneObj = transformTimeZone(props.schedule.timezone);
    initFormValues["timezone"] = timeZoneObj;
    initFormValues["workflow"] = props.workflow;

    if (props.schedule.type === "runOnce") {
      initFormValues["dateTime"] = moment
        .tz(props.schedule.dateSchedule, timeZoneObj.value)
        .format(DATETIME_LOCAL_INPUT_FORMAT);
    }

    if (when.frequency === "custom") {
      initFormValues["cronSchedule"] = (props.schedule as { cronSchedule?: string }).cronSchedule;
    }

    const scheduleLabelsMap = props.schedule.labels;
    if (scheduleLabelsMap && Object.keys(scheduleLabelsMap).length > 0) {
      initFormValues["labels"] = Object.entries(scheduleLabelsMap).map(([key, value]) => `${key}:${value}`);
    }
  }

  const requiredCount = workflowParams.filter((param) => param.required).length;

  return (
    <DynamicFormik
      enableReinitialize
      validateOnMount
      initialValues={initFormValues}
      inputs={workflowParams}
      onSubmit={({ frequency, minute, ...values }: ScheduleManagerFormInputs) => {
        const stored = frequencyToSchedule(frequency, {
          days: values.days,
          time: values.time,
          minute,
          cronSchedule: values.cronSchedule,
        });
        props.handleSubmit({ ...values, ...stored } as ScheduleManagerFormInputs, props.modalProps.closeModal);
      }}
      validationSchemaExtension={Yup.object().shape({
        name: Yup.string().required("Name is required").max(200, "Enter less than 200 characters"),
        description: Yup.string().max(500, "Enter less than 500 characters"),
        frequency: Yup.string().required("Choose when the schedule runs"),
        dateTime: Yup.string().when("frequency", {
          is: "once",
          then: Yup.string()
            .required("Date and time are required")
            .test("isAfterNow", "Enter a date and time after now", (value: string | undefined, ctx) => {
              return moment.tz(value, ctx.parent.timezone.value).isAfter(new Date());
            }),
        }),
        labels: Yup.array().max(20, "Enter less than 20 labels"),
        cronSchedule: Yup.string().when("frequency", {
          is: "custom",
          then: Yup.string()
            .required("Cron expression is required")
            .test({
              name: "isValidCron",
              test: async (value: string | undefined, { createError, path }) => {
                const result = await validateCronExpression({ workspace: workspace.name, cron: value ?? "" });
                if (result.valid) {
                  return true;
                } else {
                  return createError({
                    path,
                    message:
                      result.message ?? "Cron Expression is invalid and couldn't be converted. Please, try again.",
                  });
                }
              },
            }),
        }),
        days: Yup.array().when("frequency", {
          is: "weekly",
          then: Yup.array().min(1, "Choose at least one day"),
        }),
        time: Yup.string().when("frequency", {
          is: needsTime,
          then: Yup.string().required("Time is required").matches(TIME_PATTERN, "Enter a time as h:mm"),
        }),
        minute: Yup.number().when("frequency", {
          is: "hourly",
          then: Yup.number()
            .typeError("Enter a minute from 0 to 59")
            .required("Enter a minute from 0 to 59")
            .min(0, "Enter a minute from 0 to 59")
            .max(59, "Enter a minute from 0 to 59"),
        }),
        timezone: Yup.object().shape({ label: Yup.string(), value: Yup.string() }),
      })}
    >
      {({ inputs, formikProps }: any) => (
        <ModalForm noValidate onSubmit={formikProps.handleSubmit}>
          <ModalBody className={styles.body}>
            {props.isLoading && <Loading />}
            {props.includeWorkflowDropdown && (
              <ComboBox
                helperText="Workflow for this Schedule to execute"
                id="workflow"
                initialSelectedItem={formikProps.values.workflow}
                items={props?.workflowOptions ?? []}
                itemToString={(workflow: Workflow) => {
                  return workflow?.displayName ?? "";
                }}
                onChange={({ selectedItem }: { selectedItem: Workflow }) => {
                  formikProps.setFieldValue("workflow", selectedItem);
                  if (selectedItem?.name) {
                    setWorkflowParams(toScheduleInputs(selectedItem.params));
                  }
                }}
                placeholder="e.g. Number 1 Workflow"
                titleText="Workflow"
              />
            )}
            <div className={styles.detailsRow}>
              <TextInput
                id="name"
                invalidText={formikProps.errors.name}
                invalid={formikProps.errors.name && formikProps.touched.name}
                labelText="Name"
                onBlur={formikProps.handleBlur}
                onChange={formikProps.handleChange}
                placeholder="e.g. Nightly analysis"
                value={formikProps.values.name}
              />
              <TextInput
                id="description"
                invalid={formikProps.errors.description && formikProps.touched.description}
                invalidText={formikProps.errors.description}
                labelText="Description (optional)"
                onBlur={formikProps.handleBlur}
                onChange={formikProps.handleChange}
                placeholder="e.g. Pins the latest release every weekday"
                value={formikProps.values.description}
              />
            </div>
            <When formikProps={formikProps} />
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>Parameters</h3>
              {formikProps.values.workflow && inputs.length ? (
                <>
                  <p className={styles.sectionDescription}>Values every scheduled run starts with.</p>
                  <div className={styles.parameters}>{inputs.slice(0, requiredCount)}</div>
                  {inputs.length > requiredCount && (
                    <Accordion className={styles.optionalParameters}>
                      <AccordionItem title={`Optional parameters (${inputs.length - requiredCount})`}>
                        <div className={styles.parameters}>{inputs.slice(requiredCount)}</div>
                      </AccordionItem>
                    </Accordion>
                  )}
                </>
              ) : (
                <p className={styles.sectionDescription}>This workflow has no parameters.</p>
              )}
            </section>
            <section className={styles.section}>
              <h3 className={styles.sectionTitle}>Labels (optional)</h3>
              <Creatable
                createKeyValuePair
                keyLabelText="Label key"
                keyPlaceholder="level"
                valueLabelText="Label value"
                valuePlaceholder="important"
                value={formikProps.values.labels}
                onChange={(labels) => formikProps.setFieldValue("labels", labels)}
              />
            </section>
            {props.isError && (
              <InlineNotification
                lowContrast
                kind="error"
                title="Something's Wrong"
                subtitle={`Request to ${props.type} Schedule failed`}
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button kind="secondary" onClick={props.modalProps.closeModal}>
              Cancel
            </Button>
            <Button disabled={!formikProps.isValid || props.isLoading} type="submit">
              {props.isError
                ? "Try again"
                : props.type === "create"
                ? props.isLoading
                  ? "Creating..."
                  : "Create"
                : props.isLoading
                ? "Updating..."
                : "Update"}
            </Button>
          </ModalFooter>
        </ModalForm>
      )}
    </DynamicFormik>
  );
}

/**
 * When: one radio per choice; the chosen one opens its settings underneath. The time zone applies to all of
 * them, and a line under it says what will happen.
 */
function When({ formikProps }: { formikProps: any }) {
  const { values, errors, touched, setFieldValue, setFieldTouched, handleBlur, handleChange } = formikProps;
  const frequency: ScheduleFrequency = values.frequency;

  return (
    <fieldset className={styles.section}>
      <legend className={styles.sectionTitle}>When</legend>
      <div className={styles.frequencyList}>
        {FREQUENCIES.map((option) => {
          const checked = frequency === option.value;
          return (
            <div key={option.value} className={styles.frequencyOption}>
              <RadioButton
                checked={checked}
                id={`frequency-${option.value}`}
                labelText={option.label}
                name="frequency"
                onChange={() => setFieldValue("frequency", option.value)}
                value={option.value}
              />
              {checked && <div className={styles.frequencySettings}>{renderSettings(option.value)}</div>}
            </div>
          );
        })}
      </div>
      <div className={styles.timezone}>
        <ComboBox
          id="timezone"
          initialSelectedItem={values.timezone}
          items={timezoneOptions}
          onChange={({ selectedItem }: { selectedItem: { label: string; value: string } }) => {
            setFieldValue("timezone", selectedItem ?? { label: "", value: "" });
          }}
          placeholder="e.g. US/Central (UTC -06:00)"
          titleText="Time zone"
        />
      </div>
      <InlineNotification
        className={styles.summary}
        hideCloseButton
        kind="info"
        lowContrast
        role="status"
        subtitle={summarise(values)}
        title=""
      />
    </fieldset>
  );

  function renderSettings(option: ScheduleFrequency) {
    const timeField = (
      <TimeField
        id="time"
        invalid={Boolean(errors.time && touched.time)}
        invalidText={errors.time}
        labelText="At"
        onBlur={() => setFieldTouched("time", true)}
        onChange={(time) => setFieldValue("time", time)}
        value={values.time}
      />
    );
    switch (option) {
      case "once": {
        const [date = "", time = ""] = (values.dateTime ?? "").split("T");
        const setDateTime = (nextDate: string, nextTime: string) =>
          setFieldValue("dateTime", nextDate ? `${nextDate}T${nextTime || "09:00"}` : "");
        return (
          <div className={styles.settingsRow}>
            <DatePicker
              datePickerType="single"
              minDate={moment().format(DATE_FORMAT)}
              onChange={([picked]: Array<Date>) => setDateTime(picked ? moment(picked).format("YYYY-MM-DD") : "", time)}
              value={date ? moment(date).format(DATE_FORMAT) : ""}
            >
              <DatePickerInput
                id="dateTime"
                invalid={Boolean(errors.dateTime && touched.dateTime)}
                invalidText={errors.dateTime}
                labelText="Date"
                onBlur={() => setFieldTouched("dateTime", true)}
                placeholder="mm/dd/yyyy"
              />
            </DatePicker>
            <TimeField
              id="dateTime-time"
              labelText="At"
              onChange={(nextTime) => setDateTime(date, nextTime)}
              value={time}
            />
          </div>
        );
      }
      case "hourly":
        return (
          <div className={styles.minute}>
            <TextInput
              id="minute"
              invalid={Boolean(errors.minute && touched.minute)}
              invalidText={errors.minute}
              labelText="At minute"
              max={59}
              min={0}
              onBlur={handleBlur}
              onChange={handleChange}
              type="number"
              value={values.minute}
            />
          </div>
        );
      case "weekly":
        return (
          <div className={styles.settingsRow}>
            <fieldset className={styles.days}>
              <legend className="cds--label">On</legend>
              <div className={styles.dayButtons}>
                {DAYS_MONDAY_FIRST.map((day: DayOfWeekKey) => {
                  const picked = values.days.includes(day);
                  return (
                    <Button
                      aria-label={day[0].toUpperCase() + day.slice(1)}
                      aria-pressed={picked}
                      className={styles.dayButton}
                      key={day}
                      kind={picked ? "primary" : "tertiary"}
                      onClick={() =>
                        setFieldValue(
                          "days",
                          picked ? values.days.filter((value: DayOfWeekKey) => value !== day) : [...values.days, day],
                        )
                      }
                      size="md"
                    >
                      {day[0].toUpperCase() + day.slice(1, 2)}
                    </Button>
                  );
                })}
              </div>
              {errors.days && <div className="cds--form-requirement" style={{ display: "block" }}>{errors.days}</div>}
            </fieldset>
            {timeField}
          </div>
        );
      case "custom":
        return <CustomCron formikProps={formikProps} />;
      default:
        return timeField;
    }
  }
}

function CustomCron({ formikProps }: { formikProps: any }) {
  const { values, errors, touched, handleBlur, handleChange } = formikProps;
  let reading: string | undefined;
  try {
    reading = values.cronSchedule ? cronstrue.toString(values.cronSchedule) : undefined;
  } catch (e) {
    reading = undefined;
  }
  return (
    <div className={styles.cron}>
      <TextInput
        helperText={reading ?? "minute · hour · day of month · month · day of week"}
        id="cronSchedule"
        invalid={Boolean(errors.cronSchedule && touched.cronSchedule)}
        invalidText={errors.cronSchedule}
        labelText="Cron expression"
        onBlur={handleBlur}
        onChange={handleChange}
        placeholder="e.g. 0 18 * * *"
        value={values.cronSchedule ?? ""}
      />
      <CronInfoSection />
    </div>
  );
}

/** What the chosen settings will do, in words, with the next run where it can be worked out here. */
function summarise(values: any): string {
  const zone = values.timezone?.value || defaultTimeZone;
  const frequency: ScheduleFrequency = values.frequency;
  if (frequency === "once") {
    const at = values.dateTime ? moment(values.dateTime, DATETIME_LOCAL_INPUT_FORMAT) : undefined;
    return at?.isValid() ? `Runs once on ${at.format("ddd D MMM YYYY")} at ${at.format("h:mm A")} (${zone})` : "Choose a date and time.";
  }
  if (frequency === "custom") {
    return values.cronSchedule ? `${describeCron(values.cronSchedule)} (${zone})` : "Enter a cron expression.";
  }
  const days = frequency === "daily" ? ALL_DAYS : frequency === "weekdays" ? WEEKDAYS : values.days;
  const cron = frequency === "hourly" ? `${Number(values.minute) || 0} * * * *` : days.length ? weeklyCron(days, values.time) : "";
  if (!cron || (needsTime(frequency) && !TIME_PATTERN.test(values.time ?? ""))) {
    return frequency === "weekly" && !values.days.length ? "Choose at least one day." : "Enter a time.";
  }
  const next = nextOccurrence(frequency, { days: values.days, time: values.time, minute: values.minute, timezone: zone });
  return `Runs ${lowerFirst(describeCron(cron))} (${zone})${next ? ` · next ${next.format("ddd D MMM, h:mm A")}` : ""}`;
}

const lowerFirst = (text: string) => (text.startsWith("Every") ? `e${text.slice(1)}` : text);

const CronInfoSection: React.FC = () => {
  return (
    <Accordion>
      <AccordionItem title="Cron syntax and examples">
        <p>The cron expression is made of five fields. Each field can have the following values:</p>
        <StructuredListWrapper className={styles.cronStructuredList}>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head></StructuredListCell>
              <StructuredListCell head>minute (0-59)</StructuredListCell>
              <StructuredListCell head>hour (0-23)</StructuredListCell>
              <StructuredListCell head>day of the month (1-31)</StructuredListCell>
              <StructuredListCell head>month (1-12)</StructuredListCell>
              <StructuredListCell head>day of the week (0-6)</StructuredListCell>
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            <StructuredListRow>
              <StructuredListCell>Every minute</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
            </StructuredListRow>
            <StructuredListRow>
              <StructuredListCell>Every hour</StructuredListCell>
              <StructuredListCell>0</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
            </StructuredListRow>
            <StructuredListRow>
              <StructuredListCell>Every day at 12:00 AM</StructuredListCell>
              <StructuredListCell>0</StructuredListCell>
              <StructuredListCell>0</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
            </StructuredListRow>
            <StructuredListRow>
              <StructuredListCell>Fridays at 1:00 am</StructuredListCell>
              <StructuredListCell>0</StructuredListCell>
              <StructuredListCell>1</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>*</StructuredListCell>
              <StructuredListCell>5</StructuredListCell>
            </StructuredListRow>
          </StructuredListBody>
        </StructuredListWrapper>
      </AccordionItem>
    </Accordion>
  );
};
