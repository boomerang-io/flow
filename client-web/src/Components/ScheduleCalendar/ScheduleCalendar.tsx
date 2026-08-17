import React from "react";
import { Calendar, CalendarProps, EventProps, Event, momentLocalizer } from "react-big-calendar";
import { TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { scheduleStatusLabelMap } from "Constants";
import { CircleFilled, RadioButton } from "@carbon/react/icons";
import { CalendarEvent } from "Types";
import styles from "./ScheduleCalendar.module.scss";
import "react-big-calendar/lib/css/react-big-calendar.css";
import "./big-calendar.scss";

const localizer = momentLocalizer(moment);

interface ScheduleCalendarProps extends CalendarProps {
  events: Array<CalendarEvent>;
  heightOffset?: number;
  [key: string]: any;
}

export default function ScheduleCalendar(props: ScheduleCalendarProps) {
  const { heightOffset = 260 } = props;
  const [height, setHeight] = React.useState(window.innerHeight - heightOffset); //meh

  React.useLayoutEffect(() => {
    function updateSize() {
      setHeight(window.innerHeight - heightOffset);
    }
    window.addEventListener("resize", updateSize);
    return () => window.removeEventListener("resize", updateSize);
  });

  return (
    <Calendar<CalendarEvent>
      popup
      selectable
      // @ts-ignore
      components={{ event: CustomEvent }}
      drilldownView="agenda"
      // @ts-ignore
      localizer={localizer}
      messages={{ noEventsInRange: "" }}
      style={{ height }}
      views={["month", "agenda"]}
      eventPropGetter={() => ({
        className: styles.event,
      })}
      {...props}
    />
  );
}

interface LocalEventProps extends EventProps<Event> {
  event: CalendarEvent;
}

function CustomEvent(props: LocalEventProps) {
  const { event } = props;
  const schedule = event.resource;
  const hour = moment(event.start).format("h:mm a");
  return (
    <button className={styles.eventContentContainer} data-status={schedule.status} onClick={event.onClick}>
      <TooltipHover direction="top" tooltipText={scheduleStatusLabelMap[schedule.status] ?? "---"}>
        {schedule.status === "inactive" ? (
          <RadioButton className={styles.statusCircle} data-status={schedule.status} />
        ) : (
          <CircleFilled className={styles.statusCircle} data-status={schedule.status} />
        )}
      </TooltipHover>
      <span>
        <b>{event.title}</b>
      </span>
      <span>{hour}</span>
    </button>
  );
}
