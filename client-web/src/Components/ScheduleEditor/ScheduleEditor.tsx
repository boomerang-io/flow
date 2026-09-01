import React, { useEffect, useRef } from "react";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment-timezone";
import { useFetcher } from "react-router-dom";
import ScheduleManagerForm from "Components/ScheduleManagerForm";
import { labelStringsToRecord } from "Utils";
import { isActionError, type ActionError } from "Utils/actionResult";
import { cronDayNumberMap } from "Utils/cronHelper";
import { ScheduleManagerFormInputs, ScheduleUnion, Workflow } from "Types";
import styles from "./ScheduleEditor.module.scss";

interface ScheduleEditorProps {
  includeWorkflowDropdown?: boolean;
  isModalOpen: boolean;
  onCloseModal: () => void;
  schedule?: ScheduleUnion;
  workflow?: Workflow;
  workflowOptions?: Array<Workflow>;
}

// Matches only the fields this component reads off the owning route's action result - see the
// equivalent comment in ScheduleCreator.tsx (both routes that render this serve the
// "updateSchedule" intent through Features/Schedules/scheduleRoute.ts).
type ActionResult = { intent: string } | ({ intent: string } & ActionError);

function ScheduleEditor(props: ScheduleEditorProps) {
  const fetcher = useFetcher<ActionResult>();
  // Fetcher-settle close, same as ScheduleCreator.tsx: closeModal is stashed at submit time and
  // invoked only once the update succeeds; on failure the modal stays open with
  // ScheduleManagerForm's inline error. The old invalidateQueries + revalidator.revalidate()
  // pair is gone - the fetcher settle auto-revalidates the active loaders.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (!isActionError(fetcher.data) && fetcher.data.intent === "updateSchedule") {
      notify(
        <ToastNotification
          kind="success"
          title={`Update Schedule`}
          subtitle={`Successfully updated schedule ${props.schedule?.name} `}
        />,
      );
      closeModalRef.current?.();
      closeModalRef.current = null;
    }
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = (values: ScheduleManagerFormInputs, closeModal: () => void) => {
    if (!props.schedule) {
      return;
    }
    const {
      id,
      name,
      description,
      cronSchedule,
      dateTime,
      labels,
      timezone,
      type,
      days,
      time,
      workflow,
      ...parameters
    } = values;

    // `labels` comes off the Creatable as an array of "key:value" strings; the API takes a
    // Record<string, string> (backend `Map<String, String>` on WorkflowSchedule).
    const scheduleLabels = labelStringsToRecord(labels);

    // Undo the namespacing of parameter keys and add to parameter object
    const resetParameters: ScheduleUnion["params"] = [];
    Object.keys(parameters).forEach((paramKey) => {
      const key = paramKey.replace("$parameter:", "");
      const param = {
        name: key,
        value: parameters[paramKey],
      };
      resetParameters.push(param);
    });

    const schedule: Partial<ScheduleUnion> = {
      id: props.schedule?.id,
      description,
      name,
      type,
      labels: scheduleLabels,
      timezone: timezone.value,
      params: resetParameters,
      workflowRef: workflow.name || props.workflow?.name,
    };

    if (schedule.type === "runOnce") {
      const timeZoneDate = moment.tz(dateTime, timezone.value);
      schedule["dateSchedule"] = timeZoneDate.toISOString();
    }

    if (schedule.type === "cron") {
      let daysCron: Array<string> | [] = [];
      Object.values(days).forEach((day) => {
        //@ts-ignore
        daysCron.push(cronDayNumberMap[day]);
      });
      const timeCron = !time ? ["0", "0"] : time.split(":");
      const cronSchedule = `0 ${timeCron[1]} ${timeCron[0]} ? * ${daysCron.length !== 0 ? daysCron.toString() : "*"}`;
      schedule["cronSchedule"] = cronSchedule;
    }

    if (schedule.type === "advancedCron") {
      schedule["cronSchedule"] = cronSchedule;
    }

    closeModalRef.current = closeModal;
    fetcher.submit({ intent: "updateSchedule", schedule: JSON.stringify(schedule) }, { method: "post" });
  };

  return (
    <ComposedModal
      isOpen={Boolean(props.isModalOpen)}
      onCloseModal={props.onCloseModal}
      composedModalProps={{
        containerClassName: styles.modalContainer,
      }}
      modalHeaderProps={{
        title: "Edit a Schedule",
      }}
    >
      {(modalProps) => (
        <ScheduleManagerForm
          handleSubmit={handleSubmit}
          isError={Boolean(fetcher.data && isActionError(fetcher.data))}
          isLoading={fetcher.state !== "idle"}
          includeWorkflowDropdown={props.includeWorkflowDropdown}
          modalProps={modalProps}
          schedule={props.schedule}
          type={"edit"}
          workflow={props.workflow || props.schedule?.workflow}
          workflowOptions={props.workflowOptions}
        />
      )}
    </ComposedModal>
  );
}

export default ScheduleEditor;
