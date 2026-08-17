import React from "react";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment-timezone";
import { useMutation, useQueryClient } from "react-query";
import ScheduleManagerForm from "Components/ScheduleManagerForm";
import { useTeamContext } from "Hooks";
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
  const { team } = useTeamContext();
  /**
   * Create schedule
   */
  const { mutateAsync: createScheduleMutator, ...createScheduleMutation } = useMutation(resolver.postSchedule, {});

  const handleCreateSchedule = async (schedule: ScheduleUnion) => {
    // intentionally don't handle error so it can be done by the ScheduleManagerForm
    await createScheduleMutator({ team: team?.name, body: schedule });
    notify(
      <ToastNotification
        kind="success"
        title={`Create Schedule`}
        subtitle={`Successfully created schedule ${schedule.name} `}
      />,
    );
    queryClient.invalidateQueries(props.getCalendarUrl);
    queryClient.invalidateQueries(props.getSchedulesUrl);
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
