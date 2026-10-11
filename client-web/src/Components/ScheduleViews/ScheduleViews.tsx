import React from "react";
import { Button, ContentSwitcher, IconButton, InlineNotification, Switch } from "@carbon/react";
import { Add, Calendar as CalendarIcon, ChevronLeft, ChevronRight } from "@carbon/react/icons";
import moment from "moment-timezone";
import type { SlotInfo, View } from "react-big-calendar";
import ScheduleCalendar from "Components/ScheduleCalendar";
import SchedulePanelList from "Components/SchedulePanelList";
import type { CalendarEntry, CalendarEvent, PaginatedSchedulesResponse, ScheduleDate, ScheduleUnion } from "Types";
import styles from "./ScheduleViews.module.scss";

export type ScheduleViewId = "list" | "upcoming" | "week" | "month";

const VIEWS: Array<{ id: ScheduleViewId; label: string }> = [
  { id: "list", label: "List" },
  { id: "upcoming", label: "Upcoming" },
  { id: "week", label: "Week" },
  { id: "month", label: "Month" },
];

const CALENDAR_VIEW: Record<Exclude<ScheduleViewId, "list">, View> = { upcoming: "agenda", week: "week", month: "month" };
const VIEW_OF_CALENDAR: Partial<Record<View, ScheduleViewId>> = { agenda: "upcoming", week: "week", month: "month" };
// How far Upcoming looks ahead; ScheduleCalendar passes the same length to the agenda view.
const UPCOMING_DAYS = 30;
const STEP: Record<Exclude<ScheduleViewId, "list">, moment.unitOfTime.DurationConstructor> = {
  upcoming: "day",
  week: "week",
  month: "month",
};

/** The dates a calendar view shows around a date, which is the window the route loader reads entries for. */
export function visibleRange(view: Exclude<ScheduleViewId, "list">, date: Date): { start: Date; end: Date } {
  const at = moment(date);
  if (view === "month") {
    return { start: at.clone().startOf("month").startOf("week").toDate(), end: at.clone().endOf("month").endOf("week").toDate() };
  }
  if (view === "week") {
    return { start: at.clone().startOf("week").toDate(), end: at.clone().endOf("week").toDate() };
  }
  return { start: at.clone().startOf("day").toDate(), end: at.clone().add(UPCOMING_DAYS, "day").endOf("day").toDate() };
}

function rangeLabel(view: Exclude<ScheduleViewId, "list">, date: Date): string {
  if (view === "month") {
    return moment(date).format("MMMM YYYY");
  }
  const { start, end } = visibleRange(view, date);
  const sameYear = moment(start).year() === moment(end).year();
  return `${moment(start).format(sameYear ? "D MMMM" : "D MMMM YYYY")} – ${moment(end).format("D MMMM YYYY")}`;
}

interface ScheduleViewsProps {
  calendarEntries: Array<CalendarEntry>;
  errorLoadingCalendar?: boolean;
  heightOffset?: number;
  includeStatusFilter: boolean;
  includeWorkflowColumn?: boolean;
  onDateRangeChange: (range: { start: Date; end: Date }) => void;
  schedulesData: PaginatedSchedulesResponse | undefined;
  setActiveSchedule: (schedule: ScheduleUnion) => void;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsPanelOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setNewSchedule: React.Dispatch<React.SetStateAction<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>>;
}

/**
 * A workflow's or a workspace's schedules, as a list or on a calendar. One content switcher picks the view;
 * the calendar views share a toolbar (Today, previous, next, the dates shown).
 */
export default function ScheduleViews(props: ScheduleViewsProps) {
  const [view, setView] = React.useState<ScheduleViewId>("list");
  const [date, setDate] = React.useState<Date>(() => new Date());
  const schedules = props.schedulesData?.content ?? [];

  const show = (nextView: ScheduleViewId, nextDate: Date) => {
    setView(nextView);
    setDate(nextDate);
    if (nextView !== "list") {
      props.onDateRangeChange(visibleRange(nextView, nextDate));
    }
  };

  const selectSchedule = (schedule: ScheduleUnion) => {
    props.setActiveSchedule(schedule);
    props.setIsPanelOpen(true);
  };

  const controls = (
    <>
      <ContentSwitcher
        className={styles.switcher}
        selectedIndex={VIEWS.findIndex((option) => option.id === view)}
        size="md"
        onChange={({ index }: { index?: number }) => show(VIEWS[index ?? 0].id, view === "list" ? new Date() : date)}
      >
        {VIEWS.map((option) => (
          <Switch key={option.id} name={option.id} text={option.label} />
        ))}
      </ContentSwitcher>
      <Button className={styles.create} renderIcon={Add} size="lg" onClick={() => props.setIsCreatorOpen(true)}>
        Create schedule
      </Button>
    </>
  );

  if (view === "list") {
    return (
      <SchedulePanelList
        includeStatusFilter={props.includeStatusFilter}
        includeWorkflowColumn={props.includeWorkflowColumn}
        onSelectSchedule={selectSchedule}
        schedulesIsLoading={false}
        schedulesData={props.schedulesData}
        setActiveSchedule={props.setActiveSchedule}
        setIsCreatorOpen={props.setIsCreatorOpen}
        setIsEditorOpen={props.setIsEditorOpen}
        toolbarEnd={controls}
      />
    );
  }

  const calendarEvents: Array<CalendarEvent> = [];
  for (const calendarEntry of props.calendarEntries) {
    const schedule = schedules.find((candidate) => candidate.id === calendarEntry.scheduleId);
    if (schedule) {
      for (const entryDate of calendarEntry.dates) {
        const start = moment.tz(entryDate, schedule.timezone).toDate();
        calendarEvents.push({
          resource: schedule,
          start,
          end: start,
          title: schedule.name,
          onClick: () => selectSchedule({ ...schedule, nextScheduleDate: start.toISOString() }),
        });
      }
    }
  }

  return (
    <section className={styles.container} aria-label="Schedules calendar">
      <div className={styles.toolbar}>
        <Button className={styles.today} kind="ghost" size="lg" onClick={() => show(view, new Date())}>
          <CalendarIcon size={20} />
          Today
        </Button>
        <IconButton kind="ghost" label="Previous" size="lg" onClick={() => show(view, moment(date).subtract(1, STEP[view]).toDate())}>
          <ChevronLeft />
        </IconButton>
        <IconButton kind="ghost" label="Next" size="lg" onClick={() => show(view, moment(date).add(1, STEP[view]).toDate())}>
          <ChevronRight />
        </IconButton>
        <h2 className={styles.range}>{rangeLabel(view, date)}</h2>
        <div className={styles.toolbarEnd}>{controls}</div>
      </div>
      {props.errorLoadingCalendar ? (
        <InlineNotification
          lowContrast
          hideCloseButton={true}
          kind="error"
          title="Calendar unavailable"
          subtitle="The scheduled dates could not be loaded. The list view is still up to date."
        />
      ) : null}
      <div className={styles.calendar}>
        <ScheduleCalendar
          date={date}
          events={calendarEvents}
          heightOffset={props.heightOffset}
          onNavigate={(nextDate: Date) => setDate(nextDate)}
          onSelectEvent={(event) => {
            const { resource, start } = event as CalendarEvent;
            selectSchedule({ ...resource, nextScheduleDate: new Date(start).toISOString() });
          }}
          onSelectSlot={(slot: SlotInfo) => {
            const selectedDate = moment(slot.start);
            const isCurrentDay = selectedDate.isSame(new Date(), "day");
            if (selectedDate.isAfter() || isCurrentDay) {
              props.setNewSchedule({ dateSchedule: isCurrentDay ? moment().toISOString() : selectedDate.toISOString(), type: "runOnce" });
              props.setIsCreatorOpen(true);
            }
          }}
          // A day's number drills into Upcoming from that day.
          onView={(calendarView: View) => {
            const next = VIEW_OF_CALENDAR[calendarView];
            if (next && next !== view) {
              setView(next);
            }
          }}
          onDrillDown={(drillDate: Date) => show("upcoming", drillDate)}
          //@ts-ignore
          dayPropGetter={(day: Date) => (moment(day).isBefore(new Date(), "day") ? { style: { cursor: "initial" } } : {})}
          view={CALENDAR_VIEW[view]}
        />
      </div>
    </section>
  );
}
