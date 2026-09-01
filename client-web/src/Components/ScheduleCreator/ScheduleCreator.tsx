import React, { useEffect, useRef } from "react";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment-timezone";
import { useFetcher } from "react-router-dom";
import ScheduleManagerForm from "Components/ScheduleManagerForm";
import { labelStringsToRecord } from "Utils";
import { cronDayNumberMap } from "Utils/cronHelper";
import { ScheduleManagerFormInputs, ScheduleDate, ScheduleUnion, Workflow, DayOfWeekCronAbbreviation } from "Types";
import styles from "./ScheduleCreator.module.scss";

interface CreateScheduleProps {
  includeWorkflowDropdown?: boolean;
  isModalOpen: boolean;
  onCloseModal: () => void;
  schedule?: Pick<ScheduleDate, "dateSchedule" | "type">;
  workflow?: Workflow;
  workflowOptions?: Array<Workflow>;
}

// Matches only the fields this component reads off the owning route's action result - the real
// union lives in Features/Schedules/scheduleRoute.ts (Node-only, so components re-declare rather
// than import it; see CreateWorkflow.tsx for the precedent). This component is rendered by two
// routes - the Schedules page and the workflow editor's Schedule tab - and both route actions
// serve the "createSchedule" intent (app/routes/schedules.tsx exports scheduleAction directly;
// editorRoute.ts's editorAction dispatches SCHEDULE_INTENTS to it), so the bare useFetcher()
// resolves correctly from either surface.
type ActionResult = { ok: boolean; intent: string };

export default function CreateSchedule(props: CreateScheduleProps) {
  const fetcher = useFetcher<ActionResult>();
  // ScheduleManagerForm hands this component the modal's closeModal at submit time; the fetcher
  // settles asynchronously (fetcher.state -> "idle"), so it's stashed here with the submitted
  // schedule's name and invoked from the effect below only once the create actually succeeds -
  // the modal stays open (with ScheduleManagerForm's inline error off `isError`) on failure,
  // matching the previous mutateAsync/try-catch behaviour. Same pattern as CreateWorkflow.tsx's
  // handleImportWorkflow. The old `queryClient.invalidateQueries` + `revalidator.revalidate()`
  // pair is gone entirely: the fetcher's settle revalidates every active loader on its own.
  const closeModalRef = useRef<(() => void) | null>(null);
  const submittedNameRef = useRef<string>("");

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.ok && fetcher.data.intent === "createSchedule") {
      notify(
        <ToastNotification
          kind="success"
          title={`Create Schedule`}
          subtitle={`Successfully created schedule ${submittedNameRef.current} `}
        />,
      );
      closeModalRef.current?.();
      closeModalRef.current = null;
    }
    // Failure is inline-only (no toast), as before: ScheduleManagerForm renders its
    // "Something's Wrong" InlineNotification off the isError prop below.
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = (values: ScheduleManagerFormInputs, closeModal: () => void) => {
    const {
      advancedCron,
      cronSchedule,
      dateTime,
      description,
      id,
      labels,
      name,
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
      name,
      description,
      type,
      timezone: timezone.value,
      labels: scheduleLabels,
      params: resetParameters,
      workflowRef: workflow.name,
    };

    if (schedule.type === "runOnce") {
      const timeZoneDate = moment.tz(dateTime, timezone.value);
      schedule["dateSchedule"] = timeZoneDate.toISOString();
    }

    if (schedule.type === "cron") {
      let daysCron: Array<DayOfWeekCronAbbreviation> = [];
      for (let day of Object.values(days)) {
        daysCron.push(cronDayNumberMap[day]);
      }
      const timeCron = !time ? ["0", "0"] : time.split(":");
      const cronSchedule = `0 ${timeCron[1]} ${timeCron[0]} * ${daysCron.length !== 0 ? daysCron.toString() : "*"}`;
      schedule["cronSchedule"] = cronSchedule;
    }

    if (schedule.type === "advancedCron") {
      schedule["cronSchedule"] = cronSchedule;
    }

    closeModalRef.current = closeModal;
    submittedNameRef.current = String(schedule.name ?? "");
    fetcher.submit({ intent: "createSchedule", schedule: JSON.stringify(schedule) }, { method: "post" });
  };

  return (
    <ComposedModal
      isOpen={props.isModalOpen}
      onCloseModal={props.onCloseModal}
      composedModalProps={{
        containerClassName: styles.modalContainer,
      }}
      modalHeaderProps={{
        title: "Create a Schedule",
      }}
    >
      {(modalProps) => (
        <ScheduleManagerForm
          handleSubmit={handleSubmit}
          includeWorkflowDropdown={props.includeWorkflowDropdown}
          isError={Boolean(fetcher.data && !fetcher.data.ok)}
          isLoading={fetcher.state !== "idle"}
          modalProps={modalProps}
          schedule={props.schedule as ScheduleUnion}
          type="create"
          workflow={props.workflow}
          workflowOptions={props.workflowOptions}
        />
      )}
    </ComposedModal>
  );
}
