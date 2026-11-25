import React from "react";
import { ActionableNotification, InlineNotification, Select, SelectItem } from "@carbon/react";
import { FormikProps } from "formik";
import type { DataDrivenInput } from "Types";
import styles from "./TaskFormTabs.module.scss";

/**
 * The version a workflow node runs. A node that names a `taskVersion` is pinned to it; a node that names none
 * follows its task's latest version, which each run reads when it starts. The form carries the choice as one value
 * beside the task's params: "latest" or the pinned version's number.
 */
export const TASK_VERSION_KEY = "taskVersion";
export const LATEST_VERSION_VALUE = "latest";

export const LATEST_HELPER_TEXT = "Each run uses the version that's latest when it starts.";

export function pinnedHelperText(version: number) {
  return `Pinned: runs keep using v${version} until you change it.`;
}

/** What a node holds: a pinned version, or none (undefined or null) to follow the latest. */
export type NodeTaskVersion = number | null | undefined;

export function isLatest(taskVersion: NodeTaskVersion) {
  return taskVersion === undefined || taskVersion === null;
}

/** The node's tag on the canvas and in its edit modal's title: "Latest (v5)" or "v4". */
export function versionTagText(taskVersion: NodeTaskVersion, latestVersion: number) {
  return isLatest(taskVersion) ? `Latest (v${latestVersion})` : `v${taskVersion}`;
}

/** What the edit form knows about the node's version, supplied by the node around its form. */
export interface TaskVersionContextValue {
  /** The version the node holds now; none follows the latest. */
  taskVersion: NodeTaskVersion;
  /** The task's latest version, the one the form's params are drawn from. */
  latestVersion: number;
  /** The latest version's params, to tell which required ones the node leaves unset. */
  params: Array<DataDrivenInput>;
  /** Opens the New version modal for a pinned node; absent where there is none to open. */
  onReviewNewVersion?: () => void;
}

export const TaskVersionContext = React.createContext<TaskVersionContextValue | null>(null);

export function useTaskVersion() {
  return React.useContext(TaskVersionContext);
}

/** The form's value for the version select: the value Formik holds once changed, else the node's own. */
export function selectedVersionValue(values: Record<string, any>, taskVersion: NodeTaskVersion): string {
  const value = values[TASK_VERSION_KEY];
  if (value !== undefined) {
    return String(value);
  }
  return isLatest(taskVersion) ? LATEST_VERSION_VALUE : String(taskVersion);
}

/**
 * Takes the version off the form's values. `changed` is false when the form never set it, so the node keeps what it
 * holds; otherwise `taskVersion` is the pinned number, or null to follow the latest.
 */
export function splitTaskVersionValues<T extends Record<string, any>>(
  values: T,
): { changed: boolean; taskVersion: number | null; rest: Omit<T, typeof TASK_VERSION_KEY> } {
  const { [TASK_VERSION_KEY]: value, ...rest } = values;
  if (value === undefined) {
    return { changed: false, taskVersion: null, rest };
  }
  const pinned = Number(value);
  return {
    changed: true,
    taskVersion: value === LATEST_VERSION_VALUE || !Number.isInteger(pinned) ? null : pinned,
    rest,
  };
}

function isUnset(value: unknown) {
  return value === undefined || value === null || value === "";
}

/** The latest version's required params the form leaves empty, by their label. */
export function missingRequiredParams(params: Array<DataDrivenInput>, values: Record<string, any>): Array<string> {
  return params
    .filter((param) => param.required && isUnset(values[param.key ?? param.name]))
    .map((param) => param.label || param.name);
}

function joinNames(names: Array<string>) {
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : names[0];
}

export function missingParamsWarning(latestVersion: number, names: Array<string>) {
  const one = names.length === 1;
  return `v${latestVersion} needs ${joinNames(names)}, and this node doesn't set ${one ? "it" : "them"} yet. Fill ${
    one ? "it" : "them"
  } in on Parameters before you apply.`;
}

/** The Version section of the Configure tab: one select, its helper text, and a notification when one applies. */
export function VersionFields({
  formikProps,
  version,
}: {
  formikProps: FormikProps<any>;
  version: TaskVersionContextValue;
}) {
  const { setFieldValue, values } = formikProps;
  const { latestVersion, onReviewNewVersion, params, taskVersion } = version;
  const selected = selectedVersionValue(values, taskVersion);
  const selectedIsLatest = selected === LATEST_VERSION_VALUE;
  const pinned = Number(selected);
  const missing = selectedIsLatest ? missingRequiredParams(params, values) : [];
  const versions = Array.from({ length: latestVersion }, (_, index) => latestVersion - index);

  return (
    <fieldset className={styles.group}>
      <legend className={styles.groupLegend}>Version</legend>
      <Select
        id={TASK_VERSION_KEY}
        name={TASK_VERSION_KEY}
        labelText="Task version"
        helperText={selectedIsLatest ? LATEST_HELPER_TEXT : pinnedHelperText(pinned)}
        onChange={(event: React.ChangeEvent<HTMLSelectElement>) => setFieldValue(TASK_VERSION_KEY, event.target.value)}
        value={selected}
      >
        <SelectItem value={LATEST_VERSION_VALUE} text={`Latest (currently: v${latestVersion})`} />
        {versions.map((option) => (
          <SelectItem key={option} value={String(option)} text={`v${option}`} />
        ))}
      </Select>
      {!selectedIsLatest && pinned < latestVersion && onReviewNewVersion ? (
        <ActionableNotification
          inline
          lowContrast
          hideCloseButton
          kind="info"
          title={`v${latestVersion} is available.`}
          subtitle="Review the changes before you move this node to it."
          actionButtonLabel="Review changes"
          onActionButtonClick={onReviewNewVersion}
        />
      ) : null}
      {missing.length > 0 ? (
        <InlineNotification
          lowContrast
          hideCloseButton
          kind="warning"
          title=""
          subtitle={missingParamsWarning(latestVersion, missing)}
        />
      ) : null}
    </fieldset>
  );
}
