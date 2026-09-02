import React, { useEffect, useRef, useState } from "react";
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
import { useFetcher } from "react-router-dom";
import { isActionError, type ActionError } from "Utils/actionResult";
import { DATETIME_LOCAL_DISPLAY_FORMAT } from "Utils/dateHelper";
import { scheduleStatusOptions, scheduleStatusLabelMap, scheduleTypeLabelMap } from "Constants";
import { ScheduleStatus, ScheduleUnion, PaginatedSchedulesResponse } from "Types";
import styles from "./SchedulePanelList.module.scss";

interface SchedulePanelListProps {
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
            keys: [
              "name",
              "description",
              "type",
              "status",
              (schedule) => Object.entries(schedule.labels ?? {}).map(([key, value]) => `${key}=${value}`),
            ],
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
            id="schedules-filter"
            labelText="Filter Schedules"
            placeholder="Search Schedules"
            onChange={(e: { target: HTMLInputElement; type: "change" }) => setFilterQuery(e.target.value)}
          />
        </div>
        {props.includeStatusFilter && (
          <Layer style={{ width: "50%" }}>
            <MultiSelect
              hideLabel
              id="actions-statuses-select"
              label="Choose status(es)"
              invalid={false}
              onChange={(data: { selectedItems: Array<{ label: string; value: ScheduleStatus }> | null }) =>
                setSelectedStatuses((data.selectedItems ?? []).map((item) => item.value))
              }
              items={scheduleStatusOptions}
              selectedItems={scheduleStatusOptions.filter((option) => selectedStatuses.includes(option.value))}
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
}

// Matches only the fields this component reads off the owning route's action result - the real
// union lives in Features/Schedules/scheduleRoute.ts (Node-only; components re-declare it, see
// CreateWorkflow.tsx). Both routes that render this list serve the deleteSchedule/toggleSchedule
// intents, so the bare useFetcher() submits resolve from either surface.
type ActionResult = { intent: string } | ({ intent: string } & ActionError);

function ScheduledListItem(props: ScheduledListItemProps) {
  const deleteFetcher = useFetcher<ActionResult>();
  const toggleFetcher = useFetcher<ActionResult>();
  const [isToggleStatusModalOpen, setIsToggleStatusModalOpen] = useState(false);
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);
  // The toast wording is decided at submit time ("Disable"/"Enable"), not at settle time: by the
  // time the fetcher settles, the revalidated loader data has already flipped `isActive`, so
  // reading it in the effect would announce the opposite of what the user did.
  const toggleVerbRef = useRef<"disable" | "enable">("disable");
  // Toasts fire when the action RESULT first arrives (fetcher.data changes), not on the
  // CreateWorkflow.tsx `state === "idle"` gate: a successful delete removes this row from the
  // revalidated loader data, and React Router commits "fetcher idle" and the new loader data
  // together - this component unmounts in that same commit, so an idle-gated effect would never
  // run for exactly the success it should announce. The data arrives one commit earlier (action
  // settled, revalidation still in flight), while this row is still mounted; the refs stop the
  // effect double-firing on later re-renders with the same result object.
  const handledDeleteResultRef = useRef<ActionResult | undefined>(undefined);
  const handledToggleResultRef = useRef<ActionResult | undefined>(undefined);

  useEffect(() => {
    if (!deleteFetcher.data || deleteFetcher.data === handledDeleteResultRef.current) {
      return;
    }
    handledDeleteResultRef.current = deleteFetcher.data;
    if (deleteFetcher.data.intent !== "deleteSchedule") {
      return;
    }
    if (!isActionError(deleteFetcher.data)) {
      notify(
        <ToastNotification
          kind="success"
          title={`Delete Schedule`}
          subtitle={`Successfully deleted schedule ${props.schedule.name}`}
        />,
      );
    } else {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to delete schedule ${props.schedule.name} failed`}
        />,
      );
    }
  }, [deleteFetcher.data]);

  useEffect(() => {
    if (!toggleFetcher.data || toggleFetcher.data === handledToggleResultRef.current) {
      return;
    }
    handledToggleResultRef.current = toggleFetcher.data;
    if (toggleFetcher.data.intent !== "toggleSchedule") {
      return;
    }
    const verb = toggleVerbRef.current;
    if (!isActionError(toggleFetcher.data)) {
      notify(
        <ToastNotification
          kind="success"
          title={`${verb === "disable" ? "Disable" : "Enable"} Schedule`}
          subtitle={`Successfully ${verb}d schedule ${props.schedule.name} `}
        />,
      );
    } else {
      notify(
        <ToastNotification
          kind="error"
          title="Something's Wrong"
          subtitle={`Request to ${verb} schedule ${props.schedule.name} failed`}
        />,
      );
    }
  }, [toggleFetcher.data]);

  // Determine some things for rendering
  const isActive = props.schedule.status === "active";
  const labels: Array<React.ReactNode> = [];
  Object.entries(props.schedule.labels ?? {}).forEach(([key, value]) => {
    labels.push(
      <Tag key={key} style={{ marginLeft: 0 }} type="teal">
        {`${key}=${value}`}
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
  const handleDeleteSchedule = () => {
    deleteFetcher.submit({ intent: "deleteSchedule", id: props.schedule.id }, { method: "post" });
  };

  /**
   * Disable/enable schedule - a full-body PUT with `status` flipped, same as before.
   */
  const handleToggleStatus = () => {
    toggleVerbRef.current = isActive ? "disable" : "enable";
    const body = { ...props.schedule, status: isActive ? "inactive" : "active" };
    toggleFetcher.submit({ intent: "toggleSchedule", schedule: JSON.stringify(body) }, { method: "post" });
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
          affirmativeButtonProps={{ disabled: toggleFetcher.state !== "idle" }}
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
          affirmativeButtonProps={{ kind: "danger", disabled: deleteFetcher.state !== "idle" }}
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
