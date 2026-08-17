import React from "react";
import {
  Button,
  Layer,
  MultiSelect,
  OverflowMenu,
  OverflowMenuItem,
  SkeletonPlaceholder,
  Search,
  Tag,
  Tile,
} from "@carbon/react";
import { Add, CircleFilled, Information, RadioButton, Repeat, RepeatOne } from "@carbon/react/icons";
import { ConfirmModal, TooltipHover, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import cronstrue from "cronstrue";
import { matchSorter } from "match-sorter";
import moment from "moment-timezone";
import { useMutation, useQueryClient } from "react-query";
import { useTeamContext } from "Hooks";
import { DATETIME_LOCAL_DISPLAY_FORMAT } from "Utils/dateHelper";
import { scheduleStatusOptions, scheduleStatusLabelMap, scheduleTypeLabelMap } from "Constants";
import { resolver } from "Config/servicesConfig";
import { ScheduleUnion, PaginatedSchedulesResponse } from "Types";
import styles from "./SchedulePanelList.module.scss";

interface SchedulePanelListProps {
  getCalendarUrl: string;
  getSchedulesUrl: string;
  includeStatusFilter: boolean;
  setActiveSchedule:
    | React.Dispatch<React.SetStateAction<ScheduleUnion | undefined>>
    | ((schedule: ScheduleUnion) => void);
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  schedulesIsLoading: boolean;
  schedulesData: PaginatedSchedulesResponse | undefined;
}

export default function SchedulePanelList(props: SchedulePanelListProps) {
  const [filterQuery, setFilterQuery] = React.useState("");
  const [selectedStatuses, setSelectedStatuses] = React.useState<Array<string>>([]);

  function renderLists() {
    if (props.schedulesIsLoading) {
      return (
        <div>
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
        </div>
      );
    }

    if (props.schedulesData && props.schedulesData.numberOfElements === 0) {
      return <div style={{ marginTop: "1rem" }}>No schedules found</div>;
    }

    const schedules = props.schedulesData?.content;
    if (schedules) {
      const filteredSchedules = Boolean(filterQuery)
        ? matchSorter(schedules, filterQuery, {
            keys: ["name", "description", "type", "status", "labels.0.key", "labels.0.value"],
            threshold: matchSorter.rankings.CONTAINS,
          })
        : schedules;

      const sortedSchedules = filteredSchedules.sort((a: any, b: any) => {
        return a.name.localeCompare(b.name);
      });

      let selectedSchedules = sortedSchedules;
      if (selectedStatuses.length && props.includeStatusFilter) {
        selectedSchedules = sortedSchedules.filter((schedule: ScheduleUnion) => {
          return selectedStatuses.includes(schedule.status);
        });
      }

      if (selectedSchedules.length === 0) {
        return <div style={{ marginTop: "1rem" }}>No matching schedules found</div>;
      }

      return (
        <ul>
          {selectedSchedules.map((schedule: ScheduleUnion) => (
            <ScheduledListItem
              key={schedule.id}
              getCalendarUrl={props.getCalendarUrl}
              getSchedulesUrl={props.getSchedulesUrl}
              schedule={schedule}
              setActiveSchedule={props.setActiveSchedule}
              setIsEditorOpen={props.setIsEditorOpen}
            />
          ))}
        </ul>
      );
    } else {
      return null;
    }
  }

  const schedules = props.schedulesData?.content;

  return (
    <section className={styles.listContainer}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "1rem" }}>
        <h2>{!props.schedulesIsLoading ? `Existing Schedules (${schedules?.length ?? 0})` : "Loading Schedules..."}</h2>
        <Button size="sm" renderIcon={Add} onClick={() => props.setIsCreatorOpen(true)} kind="ghost">
          Create a Schedule
        </Button>
      </div>
      <div style={{ display: "flex", alignItems: "end", gap: "0.5rem", width: "100%" }}>
        <div style={{ width: props.includeStatusFilter ? "50%" : "100%" }}>
          <Search
            light
            id="schedules-filter"
            labelText="Filter Schedules"
            placeholder="Search Schedules"
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => setFilterQuery(e.target.value)}
          />
        </div>
        {props.includeStatusFilter && (
          <Layer style={{ width: "50%" }}>
            <MultiSelect
              hideLabel
              id="actions-statuses-select"
              label="Choose status(es)"
              placeholder="Choose status(es)"
              invalid={false}
              onChange={({ selectedItems }: { selectedItems: Array<{ key: string; value: string }> }) =>
                setSelectedStatuses(selectedItems.map((item: { key: string; value: string }) => item.value))
              }
              items={scheduleStatusOptions}
              selectedItem={selectedStatuses}
              titleText="Filter by status"
            />
          </Layer>
        )}
      </div>
      {renderLists()}
    </section>
  );
}

interface ScheduledListItemProps {
  schedule: ScheduleUnion;
  setActiveSchedule:
    | React.Dispatch<React.SetStateAction<ScheduleUnion | undefined>>
    | ((schedule: ScheduleUnion) => void);
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  getSchedulesUrl: string;
  getCalendarUrl: string;
}

function ScheduledListItem(props: ScheduledListItemProps) {
  const { team } = useTeamContext();
  const queryClient = useQueryClient();
  const [isToggleStatusModalOpen, setIsToggleStatusModalOpen] = React.useState(false);
  const [isDeleteModalOpen, setIsDeleteModalOpen] = React.useState(false);

  // Determine some things for rendering
  const isActive = props.schedule.status === "active";
  // Iterate through labels: Record<string, string>
  const labels: Array<React.ReactNode> = [];
  //TODO figure out labels
  Object.entries(props.schedule?.labels || {}).forEach(([index, value]) => {
    labels.push(
      <Tag key={index} style={{ marginLeft: 0 }} type="teal">
        {/* ${value} */}
      </Tag>,
    );
  });
  const scheduleDescription = props.schedule?.description ?? "---";
  const nextScheduledText = props.schedule.type === "runOnce" ? "Scheduled Execution" : "Next Execution";
  // Convert from UTC to configured timezone to get the correct offset, adjusting for daylight saving time
  // Then convert to the local time of the users's browser
  const nextScheduledDate = moment(
    moment.tz(props.schedule.nextScheduleDate, props.schedule?.timezone).toISOString(),
  ).format(DATETIME_LOCAL_DISPLAY_FORMAT);

  /**
   * Delete schedule
   */
  const { mutateAsync: deleteScheduleMutator, ...deleteScheduleMutation } = useMutation(resolver.deleteSchedule, {});

  const handleDeleteSchedule = async () => {
    try {
      await deleteScheduleMutator({ team: team.name, id: props.schedule.id });
      notify(
        <ToastNotification
          kind="success"
          title={`Delete Schedule`}
          subtitle={`Successfully deleted schedule ${props.schedule.name}`}
        />,
      );
      queryClient.invalidateQueries(props.getSchedulesUrl);
      queryClient.invalidateQueries(props.getCalendarUrl);
    } catch (e) {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to delete schedule ${props.schedule.name} failed`}
        />,
      );
      return;
    }
  };

  /**
   * Disable schedule
   */
  const { mutateAsync: toggleScheduleStatusMutator, ...toggleStatusMutation } = useMutation(resolver.putSchedule, {});

  const handleToggleStatus = async () => {
    const body = { ...props.schedule, status: isActive ? "inactive" : "active" };
    try {
      await toggleScheduleStatusMutator({ team: team.name, body });
      notify(
        <ToastNotification
          kind="success"
          title={`${isActive ? "Disable" : "Enable"} Schedule`}
          subtitle={`Successfully ${isActive ? "disabled" : "enabled"} schedule ${props.schedule.name} `}
        />,
      );
      queryClient.invalidateQueries(props.getSchedulesUrl);
      queryClient.invalidateQueries(props.getCalendarUrl);
    } catch (e) {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to ${isActive ? "disable" : "enable"} schedule ${props.schedule.name} failed`}
        />,
      );
      return;
    }
  };

  // Set up the Oveflow menu options
  let menuOptions = [
    {
      itemText: "Edit",
      onClick: () => {
        props.setActiveSchedule(props.schedule);
        props.setIsEditorOpen(true);
      },
    },
    {
      disabled: props.schedule.status === "trigger_disabled" || props.schedule.status === "error",
      itemText: props.schedule.status === "inactive" ? "Enable" : "Disable",
      onClick: () => setIsToggleStatusModalOpen(true),
    },
    {
      hasDivider: true,
      itemText: "Delete",
      isDelete: true,
      onClick: () => setIsDeleteModalOpen(true),
    },
  ];

  return (
    <li>
      <Tile className={styles.listItem}>
        <div className={styles.listItemTitle}>
          <h3 title={props.schedule.name}>{props.schedule.name}</h3>
          <TooltipHover direction="top" tooltipText={scheduleTypeLabelMap[props.schedule.type] ?? "---"}>
            {props.schedule.type === "runOnce" ? <RepeatOne /> : <Repeat />}
          </TooltipHover>
          <TooltipHover direction="top" tooltipText={scheduleStatusLabelMap[props.schedule.status]}>
            {props.schedule.status === "inactive" ? (
              <RadioButton className={styles.statusCircle} data-status={props.schedule.status} />
            ) : (
              <CircleFilled className={styles.statusCircle} data-status={props.schedule.status} />
            )}
          </TooltipHover>
        </div>
        <p title={scheduleDescription} className={styles.listItemDescription}>
          {scheduleDescription}
        </p>
        <dl style={{ display: "flex" }}>
          <div style={{ width: "50%" }}>
            <dt>
              {nextScheduledText}{" "}
              <TooltipHover
                direction="top"
                tooltipText={"The execution date is shown in local time based on the time zone of your browser."}
              >
                <Information />
              </TooltipHover>
            </dt>
            <dd>{nextScheduledDate}</dd>
          </div>
        </dl>
        <dl style={{ display: "flex" }}>
          <div>
            <dt>Frequency </dt>
            <dd>
              {props.schedule.type === "runOnce"
                ? "Run Once"
                : props.schedule?.cronSchedule
                ? cronstrue.toString(props.schedule?.cronSchedule)
                : "---"}
            </dd>
          </div>
        </dl>
        <dl>
          <dt>Labels</dt>
          <dd>{labels.length > 0 ? labels : "---"}</dd>
        </dl>
        <div style={{ position: "absolute", right: "0", top: "0" }}>
          <OverflowMenu flipped ariaLabel="Schedule card menu" iconDescription="Schedule menu icon" size="sm">
            {menuOptions.map(({ onClick, itemText, ...rest }, index) => (
              <OverflowMenuItem onClick={onClick} itemText={itemText} key={`${itemText}-${index}`} {...rest} />
            ))}
          </OverflowMenu>
        </div>
      </Tile>
      {isToggleStatusModalOpen && (
        <ConfirmModal
          affirmativeAction={handleToggleStatus}
          affirmativeButtonProps={{ disabled: toggleStatusMutation.isLoading }}
          affirmativeText={isActive ? "Disable" : "Enable"}
          isOpen={isToggleStatusModalOpen}
          negativeAction={() => {
            setIsToggleStatusModalOpen(false);
          }}
          negativeText="Cancel"
          onCloseModal={() => {
            setIsToggleStatusModalOpen(false);
          }}
          title={`${isActive ? "Disable" : "Enable"} Schedule?`}
        >
          {`Are you sure you want to ${isActive ? "disable" : "enable"} schedule ${
            props.schedule.name
          }? Don't worry, you can change it in the future.`}
        </ConfirmModal>
      )}
      {isDeleteModalOpen && (
        <ConfirmModal
          affirmativeAction={handleDeleteSchedule}
          affirmativeButtonProps={{ kind: "danger", disabled: deleteScheduleMutation.isLoading }}
          affirmativeText="Delete"
          isOpen={isDeleteModalOpen}
          negativeAction={() => {
            setIsDeleteModalOpen(false);
          }}
          negativeText="Cancel"
          onCloseModal={() => {
            setIsDeleteModalOpen(false);
          }}
          title={`Delete Schedule?`}
        >
          {`Are you sure you want to delete schedule ${props.schedule.name}? There's no going back from this decision.`}
        </ConfirmModal>
      )}
    </li>
  );
}
