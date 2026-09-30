import React, { useEffect, useRef, useState } from "react";
import {
  Layer,
  MultiSelect,
  OverflowMenu,
  OverflowMenuItem,
  Search,
  SkeletonPlaceholder,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Tag,
} from "@carbon/react";
import { ConfirmModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { matchSorter } from "match-sorter";
import moment from "moment-timezone";
import { useFetcher } from "react-router-dom";
import { isActionError, type ActionError } from "Utils/actionResult";
import { describeCron } from "Utils/cronHelper";
import { scheduleStatusOptions, scheduleStatusLabelMap } from "Constants";
import { ScheduleStatus, ScheduleUnion, PaginatedSchedulesResponse } from "Types";
import styles from "./SchedulePanelList.module.scss";

interface SchedulePanelListProps {
  includeStatusFilter: boolean;
  includeWorkflowColumn?: boolean;
  onSelectSchedule?: (schedule: ScheduleUnion) => void;
  setActiveSchedule:
    | React.Dispatch<React.SetStateAction<ScheduleUnion | undefined>>
    | ((schedule: ScheduleUnion) => void);
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  schedulesIsLoading: boolean;
  schedulesData: PaginatedSchedulesResponse | undefined;
  // The view switcher and Create schedule button, at the right of the toolbar.
  toolbarEnd?: React.ReactNode;
}

const statusTagType: Record<ScheduleStatus, "green" | "gray" | "cool-gray" | "red" | "blue"> = {
  active: "green",
  inactive: "gray",
  trigger_disabled: "cool-gray",
  error: "red",
  deleted: "red",
  completed: "blue",
};

/** When a schedule runs, in words: "Once on 30 Sep 2026, 1:30 PM", "Monday to Friday at 9:00 AM", or the cron read aloud. */
export function describeSchedule(schedule: ScheduleUnion): string {
  if (schedule.type === "runOnce") {
    const at = moment.tz(schedule.dateSchedule, schedule.timezone);
    return at.isValid() ? `Once on ${at.format("D MMM YYYY, h:mm A")}` : "Once";
  }
  return describeCron(schedule.cronSchedule, schedule.type === "cron");
}

// The next run in the viewer's own time zone; only an active schedule has one to show.
function nextRun(schedule: ScheduleUnion): string {
  if (schedule.status !== "active" || !schedule.nextScheduleDate) {
    return "---";
  }
  return moment(moment.tz(schedule.nextScheduleDate, schedule.timezone).toISOString()).format("ddd D MMM, h:mm A");
}

export default function SchedulePanelList(props: SchedulePanelListProps) {
  const [filterQuery, setFilterQuery] = React.useState("");
  const [selectedStatuses, setSelectedStatuses] = React.useState<Array<string>>([]);

  const headers = [
    { key: "name", header: "Name" },
    ...(props.includeWorkflowColumn ? [{ key: "workflow", header: "Workflow" }] : []),
    { key: "when", header: "When" },
    { key: "next", header: "Next run" },
    { key: "timezone", header: "Time zone" },
    { key: "status", header: "Status" },
    { key: "actions", header: "" },
  ];

  function renderRows() {
    if (props.schedulesIsLoading) {
      return (
        <div>
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
          <SkeletonPlaceholder className={styles.listItemSkeleton} />
        </div>
      );
    }

    const schedules = props.schedulesData?.content ?? [];
    if (schedules.length === 0) {
      return <p className={styles.empty}>No schedules yet. Create one to run this on a timetable.</p>;
    }

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

    let selectedSchedules = [...filteredSchedules].sort((a, b) => a.name.localeCompare(b.name));
    if (selectedStatuses.length && props.includeStatusFilter) {
      selectedSchedules = selectedSchedules.filter((schedule) => selectedStatuses.includes(schedule.status));
    }

    if (selectedSchedules.length === 0) {
      return <p className={styles.empty}>No matching schedules found</p>;
    }

    return (
      <Table aria-label="Schedules" className={styles.table} size="lg">
        <TableHead>
          <TableRow>
            {headers.map((header) => (
              <TableHeader key={header.key}>{header.header}</TableHeader>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {selectedSchedules.map((schedule) => (
            <TableRow key={schedule.id}>
              <TableCell>
                {props.onSelectSchedule ? (
                  <button className={styles.nameButton} onClick={() => props.onSelectSchedule?.(schedule)}>
                    {schedule.name}
                  </button>
                ) : (
                  <span className={styles.name}>{schedule.name}</span>
                )}
                {schedule.description && <span className={styles.description}>{schedule.description}</span>}
                {Object.keys(schedule.labels ?? {}).length > 0 && (
                  <span className={styles.labels}>
                    {Object.entries(schedule.labels ?? {}).map(([key, value]) => (
                      <Tag key={key} size="sm" type="cool-gray">{`${key}=${value}`}</Tag>
                    ))}
                  </span>
                )}
              </TableCell>
              {props.includeWorkflowColumn && <TableCell>{schedule.workflow?.displayName ?? schedule.workflowRef}</TableCell>}
              <TableCell>
                <span>{describeSchedule(schedule)}</span>
                {schedule.type === "advancedCron" && <span className={styles.cron}>{schedule.cronSchedule}</span>}
              </TableCell>
              <TableCell>{nextRun(schedule)}</TableCell>
              <TableCell>{schedule.timezone}</TableCell>
              <TableCell>
                <Tag size="sm" type={statusTagType[schedule.status]}>
                  {scheduleStatusLabelMap[schedule.status]}
                </Tag>
              </TableCell>
              <TableCell className={styles.actionsCell}>
                <ScheduleRowActions
                  schedule={schedule}
                  setActiveSchedule={props.setActiveSchedule}
                  setIsEditorOpen={props.setIsEditorOpen}
                />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );
  }

  return (
    <section className={styles.listContainer} aria-label="Schedules list">
      <div className={styles.toolbar}>
        <Search
          className={styles.search}
          id="schedules-filter"
          labelText="Filter schedules"
          placeholder="Search schedules"
          size="lg"
          onChange={(e: { target: HTMLInputElement; type: "change" }) => setFilterQuery(e.target.value)}
        />
        {props.includeStatusFilter && (
          <Layer className={styles.statusFilter}>
            <MultiSelect
              hideLabel
              id="actions-statuses-select"
              label="All statuses"
              invalid={false}
              onChange={(data: { selectedItems: Array<{ label: string; value: ScheduleStatus }> | null }) =>
                setSelectedStatuses((data.selectedItems ?? []).map((item) => item.value))
              }
              items={scheduleStatusOptions}
              selectedItems={scheduleStatusOptions.filter((option) => selectedStatuses.includes(option.value))}
              size="lg"
              titleText="Filter by status"
            />
          </Layer>
        )}
        <div className={styles.toolbarEnd}>{props.toolbarEnd}</div>
      </div>
      {renderRows()}
    </section>
  );
}

interface ScheduleRowActionsProps {
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

function ScheduleRowActions(props: ScheduleRowActionsProps) {
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
    <>
      <OverflowMenu align="left" flipped aria-label="Schedule menu" iconDescription="Schedule menu icon" size="md">
        {menuOptions.map(({ onClick, itemText, ...rest }, index) => (
          <OverflowMenuItem onClick={onClick} itemText={itemText} key={`${itemText}-${index}`} {...rest} />
        ))}
      </OverflowMenu>
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
    </>
  );
}
