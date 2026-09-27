import React from "react";
import { Tab, TabList, TabPanel, TabPanels, Tabs, Tag } from "@carbon/react";
import { WarningFilled } from "@carbon/react/icons";
import { TextArea, Toggle } from "@boomerang-io/carbon-addons-boomerang-react";
import { FormikProps } from "formik";
import styles from "./TaskFormTabs.module.scss";
import { FOREACH_ENABLED_KEY, FOREACH_ITEMS_KEY, FOREACH_KEYS, FOREACH_MAX_ITEMS, FOREACH_TASK_TYPES } from "./foreach";

// The input DynamicFormik renders above the tabs rather than inside them.
const TASK_NAME_KEY = "taskName";

interface TaskFormTabsProps {
  /** The rendered inputs DynamicFormik hands its children, each keyed by its input key. */
  inputs: Array<React.ReactNode>;
  formikProps: FormikProps<any>;
  /** The node type of the task being edited; only FOREACH_TASK_TYPES get the Configure tab. */
  taskType?: string;
}

/**
 * The body of every task edit form: "Task Name" above two tabs, Parameters (the task's own
 * inputs and its results) and Configure (run the task for each item of a list). One Formik form
 * spans both tabs, so Apply saves both. A task type that cannot run for each item has nothing to
 * configure, so its form is the inputs alone, with no tab bar.
 */
export default function TaskFormTabs({ inputs, formikProps, taskType }: TaskFormTabsProps) {
  const { errors, values } = formikProps;
  if (!taskType || !FOREACH_TASK_TYPES.includes(taskType)) {
    return <>{inputs}</>;
  }
  const isNameInput = (input: React.ReactNode) => React.isValidElement(input) && input.key === TASK_NAME_KEY;
  const nameInputs = inputs.filter(isNameInput);
  const parameterInputs = inputs.filter((input) => !isNameInput(input));

  const errorKeys = Object.keys(errors).filter((key) => Boolean(errors[key]));
  const hasConfigureError = errorKeys.some((key) => FOREACH_KEYS.includes(key));
  const hasParametersError = errorKeys.some((key) => key !== TASK_NAME_KEY && !FOREACH_KEYS.includes(key));
  const isForeachEnabled = Boolean(values[FOREACH_ENABLED_KEY]);

  return (
    <>
      {nameInputs}
      <Tabs>
        <TabList aria-label="Task configuration" className={styles.tabList}>
          <Tab>
            <span className={styles.tabLabel}>
              Parameters
              {hasParametersError ? <TabErrorIcon tabName="Parameters" /> : null}
            </span>
          </Tab>
          <Tab>
            <span className={styles.tabLabel}>
              Configure
              {isForeachEnabled ? (
                <Tag as="span" className={styles.tag} size="sm" type="purple">
                  For each
                </Tag>
              ) : null}
              {hasConfigureError ? <TabErrorIcon tabName="Configure" /> : null}
            </span>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel className={styles.tabPanel}>{parameterInputs}</TabPanel>
          <TabPanel className={styles.tabPanel}>
            <ForeachFields formikProps={formikProps} />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </>
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
            helperText={`A JSON array from a parameter or an earlier task's result. At most ${FOREACH_MAX_ITEMS} items.`}
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
