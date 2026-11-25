import React from "react";
import { ActionableNotification, InlineNotification, Select, SelectItem } from "@carbon/react";
import { FormikProps } from "formik";
import type { DataDrivenInput } from "Types";
import styles from "./TaskFormTabs.module.scss";

/**
 * The version a workflow node runs. A number pins the node to it; "latest" follows the task's latest version, which
 * each run reads when it starts. A node with neither is pinned to the latest when the workflow is saved. The form
 * carries the choice as one value beside the task's params: "latest" or the pinned version's number.
 */
export const TASK_VERSION_KEY = "taskVersion";
export const LATEST_VERSION_VALUE = "latest";

export const LATEST_HELPER_TEXT = "Each run uses the version that's latest when it starts.";

export function pinnedHelperText(version: number) {
  return `Pinned: runs keep using v${version} until you change it.`;
}

/** What a node holds: a pinned version, "latest", or nothing yet, which the save pins to the latest. */
export type NodeTaskVersion = number | typeof LATEST_VERSION_VALUE | null | undefined;

export function isLatest(taskVersion: NodeTaskVersion) {
  return taskVersion === LATEST_VERSION_VALUE;
}

/** The version a pinned node runs: its own, or the latest for one the save has yet to pin. */
function pinnedVersion(taskVersion: NodeTaskVersion, latestVersion: number) {
  const pinned = Number(taskVersion);
  return taskVersion !== null && taskVersion !== undefined && Number.isInteger(pinned) ? pinned : latestVersion;
}

/** The node's tag on the canvas and in its edit modal's title: "Latest (v5)" or "v4". */
export function versionTagText(taskVersion: NodeTaskVersion, latestVersion: number) {
  return isLatest(taskVersion) ? `Latest (v${latestVersion})` : `v${pinnedVersion(taskVersion, latestVersion)}`;
}

/** What the edit form knows about the node's version, supplied by the node around its form. */
export interface TaskVersionContextValue {
  /** The version the node holds now. */
  taskVersion: NodeTaskVersion;
  /** The task's latest version, the one the form's params are drawn from. */
  latestVersion: number;
  /** The latest version's params, to tell which ones the node leaves unset. */
  params: Array<DataDrivenInput>;
  /** Opens the New version modal for a pinned node; absent where there is none to open. */
  onReviewNewVersion?: () => void;
}

export const TaskVersionContext = React.createContext<TaskVersionContextValue | null>(null);

export function useTaskVersion() {
  return React.useContext(TaskVersionContext);
}

/** The form's value for the version select: the value Formik holds once changed, else the node's own. */
export function selectedVersionValue(
  values: Record<string, any>,
  taskVersion: NodeTaskVersion,
  latestVersion: number,
): string {
  const value = values[TASK_VERSION_KEY];
  if (value !== undefined) {
    return String(value);
  }
  return isLatest(taskVersion) ? LATEST_VERSION_VALUE : String(pinnedVersion(taskVersion, latestVersion));
}

/**
 * Takes the version off the form's values. `changed` is false when the form never set it, so the node keeps what it
 * holds; otherwise `taskVersion` is "latest" or the pinned number.
 */
export function splitTaskVersionValues<T extends Record<string, any>>(
  values: T,
): {
  changed: boolean;
  taskVersion: number | typeof LATEST_VERSION_VALUE | undefined;
  rest: Omit<T, typeof TASK_VERSION_KEY>;
} {
  const { [TASK_VERSION_KEY]: value, ...rest } = values;
  if (value === undefined) {
    return { changed: false, taskVersion: undefined, rest };
  }
  const pinned = Number(value);
  return {
    changed: true,
    taskVersion: value === LATEST_VERSION_VALUE || !Number.isInteger(pinned) ? LATEST_VERSION_VALUE : pinned,
    rest,
  };
}

function isUnset(value: unknown) {
  return value === undefined || value === null || value === "";
}

/** The latest version's params, required or not, that the form leaves empty, by their label. */
export function unsetParams(params: Array<DataDrivenInput>, values: Record<string, any>): Array<string> {
  return params.filter((param) => isUnset(values[param.key ?? param.name])).map((param) => param.label || param.name);
}

export function unsetParamsNotice(latestVersion: number, names: Array<string>) {
  return `v${latestVersion} has fields this node doesn't set: ${names.join(", ")}.`;
}

/** The Version section of the Configure tab: one select, its helper text, and a notice when one applies. */
export function VersionFields({
  formikProps,
  version,
}: {
  formikProps: FormikProps<any>;
  version: TaskVersionContextValue;
}) {
  const { setFieldValue, values } = formikProps;
  const { latestVersion, onReviewNewVersion, params, taskVersion } = version;
  const selected = selectedVersionValue(values, taskVersion, latestVersion);
  const selectedIsLatest = selected === LATEST_VERSION_VALUE;
  const pinned = Number(selected);
  const unset = selectedIsLatest ? unsetParams(params, values) : [];
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
      {unset.length > 0 ? (
        <InlineNotification
          lowContrast
          hideCloseButton
          kind="info"
          title=""
          subtitle={unsetParamsNotice(latestVersion, unset)}
        />
      ) : null}
    </fieldset>
  );
}
