import React from "react";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment-timezone";
import { useMutation, useQueryClient } from "react-query";
import { useRevalidator } from "react-router-dom";
import ScheduleManagerForm from "Components/ScheduleManagerForm";
import { useWorkspaceContext } from "Hooks";
import { cronDayNumberMap } from "Utils/cronHelper";
import { resolver } from "Config/servicesConfig";
import { ScheduleManagerFormInputs, ScheduleDate, ScheduleUnion, Workflow, DayOfWeekCronAbbreviation } from "Types";
import styles from "./ScheduleCreator.module.scss";

interface CreateScheduleProps {
  getCalendarUrl: string;
  getSchedulesUrl: string;
  includeWorkflowDropdown?: boolean;
  isModalOpen: boolean;
  onCloseModal: () => void;
  schedule?: Pick<ScheduleDate, "dateSchedule" | "type">;
  workflow?: Workflow;
  workflowOptions?: Array<Workflow>;
}

export default function CreateSchedule(props: CreateScheduleProps) {
  const queryClient = useQueryClient();
  const { workspace } = useWorkspaceContext();
  // This component is rendered by two surfaces: this Schedules page (route-loader-driven, see
  // Features/Schedules/Schedules.tsx) and WorkflowEditor/Schedule/Schedule.tsx's Schedule tab
  // (still react-query-driven). It stays on `useMutation` rather than moving to a
  // useFetcher()/route-action write - ScheduleManagerForm's onSubmit awaits `handleSubmit`
  // synchronously to decide whether to close the modal, and Schedule.tsx has no matching route
  // action to submit to. `queryClient.invalidateQueries` below keeps that unconverted consumer's
  // own useQuery refreshing exactly as before; `revalidator.revalidate()` is added alongside it
  // purely so this page's loader-driven read also refreshes (a no-op invalidateQueries here, same
  // as GlobalTokens.tsx's CreateToken).
  const revalidator = useRevalidator();
  /**
   * Create schedule
   */
  const { mutateAsync: createScheduleMutator, ...createScheduleMutation } = useMutation(resolver.postSchedule, {});

  const handleCreateSchedule = async (schedule: ScheduleUnion) => {
    // intentionally don't handle error so it can be done by the ScheduleManagerForm
    await createScheduleMutator({ workspace: workspace?.name, body: schedule });
    notify(
      <ToastNotification
        kind="success"
        title={`Create Schedule`}
        subtitle={`Successfully created schedule ${schedule.name} `}
      />,
    );
    queryClient.invalidateQueries(props.getCalendarUrl);
    queryClient.invalidateQueries(props.getSchedulesUrl);
    revalidator.revalidate();
    return;
  };

  const handleSubmit = async (values: ScheduleManagerFormInputs) => {
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

    let scheduleLabels: Record<string, string> = {};
    // if (values.labels.length) {
    //   scheduleLabels = values.labels.map((pair: string) => {
    //     const [key, value] = pair.split(":");
    //     return { key, value };
    //   });
    // }

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

    return await handleCreateSchedule(schedule as ScheduleUnion);
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
          isError={createScheduleMutation.isError}
          isLoading={createScheduleMutation.isLoading}
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
