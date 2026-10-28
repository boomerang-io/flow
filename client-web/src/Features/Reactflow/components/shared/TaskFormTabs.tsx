import React from "react";
import {
  Select,
  SelectItem,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Tag,
  TextInput as CarbonTextInput,
} from "@carbon/react";
import { WarningFilled } from "@carbon/react/icons";
import { TextArea, Toggle } from "@boomerang-io/carbon-addons-boomerang-react";
import { FormikProps } from "formik";
import styles from "./TaskFormTabs.module.scss";
import {
  FOREACH_ENABLED_KEY,
  FOREACH_ITEMS_HELPER_TEXT,
  FOREACH_ITEMS_KEY,
  FOREACH_KEYS,
  FOREACH_TASK_TYPES,
} from "./foreach";
import {
  RETRY_BACKOFF_INITIAL_KEY,
  RETRY_BACKOFF_MAX_KEY,
  RETRY_BACKOFF_OPTIONS,
  RETRY_BACKOFF_TYPE_KEY,
  RETRY_KEYS,
  RETRY_MAX_HELPER_TEXT,
  RETRY_MAX_KEY,
} from "./retry";

// The input DynamicFormik renders in the header block rather than on a tab.
const TASK_NAME_KEY = "taskName";

// Every form value the Configure tab owns.
const CONFIGURE_KEYS = [...FOREACH_KEYS, ...RETRY_KEYS];

type TaskResult = { name: string; description: string };

interface TaskFormTabsProps {
  /** The rendered inputs DynamicFormik hands its children, each keyed by its input key. */
  inputs: Array<React.ReactNode>;
  formikProps: FormikProps<any>;
  /** The node type of the task being edited; only FOREACH_TASK_TYPES (the types that retry too) get the Configure tab. */
  taskType?: string;
  /** The results the task declares, listed read-only on a Results tab; none means no tab. */
  results?: Array<TaskResult>;
}

/**
 * The body of every task edit form. A header block carries the task name and the tab list, so
 * the modal's own header, the name and the tabs read as one; the tab panel follows. The tabs
 * are Parameters (the task's own inputs), Configure (retry and run the task for each item of a list,
 * for a type that can) and Results (what the task declares, when it declares any). One Formik form
 * spans every tab, so Apply saves them all. A type with nothing to configure and no results
 * has no tabs: the name alone sits above its inputs.
 */
export default function TaskFormTabs({ inputs, formikProps, taskType, results = [] }: TaskFormTabsProps) {
  const { errors, values } = formikProps;
  const isNameInput = (input: React.ReactNode) => React.isValidElement(input) && input.key === TASK_NAME_KEY;
  const nameInputs = inputs.filter(isNameInput);
  const parameterInputs = inputs.filter((input) => !isNameInput(input));
  const hasConfigure = Boolean(taskType) && FOREACH_TASK_TYPES.includes(taskType as string);
  const hasResults = results.length > 0;

  if (!hasConfigure && !hasResults) {
    return (
      <>
        <div className={styles.header}>{nameInputs}</div>
        <div className={styles.fields}>{parameterInputs}</div>
      </>
    );
  }

  const errorKeys = Object.keys(errors).filter((key) => Boolean(errors[key]));
  const hasConfigureError = errorKeys.some((key) => CONFIGURE_KEYS.includes(key));
  const hasParametersError = errorKeys.some((key) => key !== TASK_NAME_KEY && !CONFIGURE_KEYS.includes(key));
  const isForeachEnabled = Boolean(values[FOREACH_ENABLED_KEY]);

  // Carbon pairs tabs and panels by position, so both lists are built from one array with no gaps.
  const tabs: Array<{ name: string; label: React.ReactNode; panel: React.ReactNode }> = [
    {
      name: "Parameters",
      label: (
        <span className={styles.tabLabel}>
          Parameters
          {hasParametersError ? <TabErrorIcon tabName="Parameters" /> : null}
        </span>
      ),
      panel: parameterInputs,
    },
  ];
  if (hasConfigure) {
    tabs.push({
      name: "Configure",
      label: (
        <span className={styles.tabLabel}>
          Configure
          {isForeachEnabled ? (
            <Tag as="span" className={styles.tag} size="sm" type="purple">
              For each
            </Tag>
          ) : null}
          {hasConfigureError ? <TabErrorIcon tabName="Configure" /> : null}
        </span>
      ),
      panel: <ConfigureFields formikProps={formikProps} />,
    });
  }
  if (hasResults) {
    tabs.push({ name: "Results", label: "Results", panel: <ResultsList results={results} /> });
  }

  return (
    <Tabs>
      <div className={styles.header}>
        {nameInputs}
        <TabList aria-label="Task configuration" className={styles.tabList}>
          {tabs.map((tab) => (
            <Tab key={tab.name}>{tab.label}</Tab>
          ))}
        </TabList>
      </div>
      <TabPanels>
        {tabs.map((tab) => (
          <TabPanel key={tab.name} className={styles.tabPanel}>
            {tab.panel}
          </TabPanel>
        ))}
      </TabPanels>
    </Tabs>
  );
}

function TabErrorIcon({ tabName }: { tabName: string }) {
  return (
    <WarningFilled
      aria-label={`${tabName} has an error`}
      className={styles.errorIcon}
      role="img"
      style={{ willChange: "auto" }}
    />
  );
}

// The results a task declares are the template's, not the node's to edit: each is read as
// `$(tasks.<name>.results.<result>)` by the tasks after it.
function ResultsList({ results }: { results: Array<TaskResult> }) {
  return (
    <ul className={styles.results} aria-label="Results">
      {results.map((result) => (
        <li key={result.name} className={styles.result}>
          <code className={styles.resultName}>{result.name}</code>
          {result.description ? <p className={styles.resultDescription}>{result.description}</p> : null}
        </li>
      ))}
    </ul>
  );
}

function ConfigureFields({ formikProps }: { formikProps: FormikProps<any> }) {
  return (
    <div className={styles.configure}>
      <RetryFields formikProps={formikProps} />
      <ForeachFields formikProps={formikProps} />
    </div>
  );
}

function RetryFields({ formikProps }: { formikProps: FormikProps<any> }) {
  const { errors, handleBlur, handleChange, touched, values } = formikProps;
  const isFixed = values[RETRY_BACKOFF_TYPE_KEY] === "fixed";
  const fieldError = (key: string) => (typeof errors[key] === "string" ? (errors[key] as string) : undefined);

  return (
    <fieldset className={styles.group}>
      <legend className={styles.groupLegend}>Retry</legend>
      <CarbonTextInput
        id={RETRY_MAX_KEY}
        name={RETRY_MAX_KEY}
        type="number"
        min={0}
        max={10}
        labelText="Retries"
        helperText={RETRY_MAX_HELPER_TEXT}
        invalid={Boolean(fieldError(RETRY_MAX_KEY)) && Boolean(touched[RETRY_MAX_KEY])}
        invalidText={fieldError(RETRY_MAX_KEY)}
        onBlur={handleBlur}
        onChange={handleChange}
        value={values[RETRY_MAX_KEY] ?? ""}
      />
      <div className={styles.backoff}>
        <Select
          id={RETRY_BACKOFF_TYPE_KEY}
          name={RETRY_BACKOFF_TYPE_KEY}
          labelText="Backoff"
          onChange={handleChange}
          value={values[RETRY_BACKOFF_TYPE_KEY] ?? "exponential"}
        >
          {RETRY_BACKOFF_OPTIONS.map((option) => (
            <SelectItem key={option.value} value={option.value} text={option.label} />
          ))}
        </Select>
        <CarbonTextInput
          id={RETRY_BACKOFF_INITIAL_KEY}
          name={RETRY_BACKOFF_INITIAL_KEY}
          type="number"
          min={1}
          labelText="Initial wait (seconds)"
          placeholder="10"
          invalid={Boolean(fieldError(RETRY_BACKOFF_INITIAL_KEY)) && Boolean(touched[RETRY_BACKOFF_INITIAL_KEY])}
          invalidText={fieldError(RETRY_BACKOFF_INITIAL_KEY)}
          onBlur={handleBlur}
          onChange={handleChange}
          value={values[RETRY_BACKOFF_INITIAL_KEY] ?? ""}
        />
        <CarbonTextInput
          id={RETRY_BACKOFF_MAX_KEY}
          name={RETRY_BACKOFF_MAX_KEY}
          type="number"
          min={1}
          labelText="Maximum wait (seconds)"
          placeholder="300"
          disabled={isFixed}
          invalid={Boolean(fieldError(RETRY_BACKOFF_MAX_KEY)) && Boolean(touched[RETRY_BACKOFF_MAX_KEY])}
          invalidText={fieldError(RETRY_BACKOFF_MAX_KEY)}
          onBlur={handleBlur}
          onChange={handleChange}
          value={values[RETRY_BACKOFF_MAX_KEY] ?? ""}
        />
      </div>
    </fieldset>
  );
}

function ForeachFields({ formikProps }: { formikProps: FormikProps<any> }) {
  const { errors, handleBlur, handleChange, setFieldTouched, setFieldValue, touched, values } = formikProps;
  const isEnabled = Boolean(values[FOREACH_ENABLED_KEY]);
  const itemsError = errors[FOREACH_ITEMS_KEY];

  return (
    <div className={styles.configure}>
      <Toggle
        id={FOREACH_ENABLED_KEY}
        label="Run for each item"
        onToggle={(checked: boolean) => {
          setFieldValue(FOREACH_ENABLED_KEY, checked);
          if (!checked) {
            setFieldTouched(FOREACH_ITEMS_KEY, false, false);
          }
        }}
        toggled={isEnabled}
      />
      {isEnabled ? (
        <>
          <TextArea
            id={FOREACH_ITEMS_KEY}
            name={FOREACH_ITEMS_KEY}
            labelText="Items"
            helperText={FOREACH_ITEMS_HELPER_TEXT}
            placeholder="$(tasks.stage.results.batches)"
            invalid={Boolean(itemsError) && Boolean(touched[FOREACH_ITEMS_KEY])}
            invalidText={typeof itemsError === "string" ? itemsError : undefined}
            onBlur={handleBlur}
            onChange={handleChange}
            value={values[FOREACH_ITEMS_KEY] ?? ""}
          />
          <ForeachExplainer />
        </>
      ) : null}
    </div>
  );
}

export function ForeachExplainer() {
  return (
    <ul className={styles.explainer} aria-label="How for each works">
      <li>
        Inside the task, <code>$(params.item)</code> is the current item and <code>$(params.index)</code> its position
        from 0.
      </li>
      <li>
        After the task, <code>{"$(tasks.<name>.results.<result>)"}</code> is an array in item order, with null for an
        item that failed.
      </li>
      <li>If any item fails, the task fails. To carry on anyway, set the connection to the next task to always.</li>
    </ul>
  );
}
