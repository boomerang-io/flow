import React from "react";
import { Button, CodeSnippet, IconButton, Tag } from "@carbon/react";
import { TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import cronstrue from "cronstrue";
import cx from "classnames";
import moment from "moment-timezone";
import { DATETIME_LOCAL_DISPLAY_FORMAT } from "Utils/dateHelper";
import { scheduleStatusLabelMap, scheduleTypeLabelMap } from "Constants";
import {
  CircleFilled,
  Close,
  Information,
  SettingsAdjust,
  RadioButton,
  Repeat,
  RepeatOne,
} from "@carbon/react/icons";
import { ScheduleUnion } from "Types";
import styles from "./SchedulePanelDetail.module.scss";

interface SchedulePanelDetailProps {
  className: string;
  event?: ScheduleUnion;
  isOpen: boolean;
  setIsOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
}

/**
 * The schedule's detail in a panel that slides in from the right over the page. It is the page's own
 * element, not a modal library's: Escape, the overlay and the close button all close it.
 */
export default function SchedulePanelDetail(props: SchedulePanelDetailProps) {
  const schedule = props.event;
  const { isOpen, setIsOpen } = props;
  const panelRef = React.useRef<HTMLElement>(null);

  React.useEffect(() => {
    if (!isOpen) {
      return;
    }
    panelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setIsOpen(false);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [isOpen, setIsOpen]);

  function renderSchedule() {
    if (!schedule || !props.isOpen) {
      return <></>;
    }

    const scheduleDescription = schedule?.description ?? "---";
    // Convert from UTC to configured timezone to get the correct offset, adjusting for daylight saving time
    // Then convert to the local time of the users's browser
    const nextScheduledDate = moment(moment.tz(schedule.nextScheduleDate, schedule.timezone).toISOString()).format(
      DATETIME_LOCAL_DISPLAY_FORMAT,
    );

    const labels: React.ReactNode[] = [];
    Object.entries(schedule.labels ?? {}).forEach(([key, value]) => {
      labels.push(
        <Tag key={key} style={{ marginLeft: 0 }} type="teal">
          {`${key}=${value}`}
        </Tag>,
      );
    });

    return (
      <>
        <div className={styles.detailsContainer}>
          <section className={styles.detailsInfo}>
            <div className={styles.detailsTitle}>
              <h2 title={schedule.name}>{schedule.name}</h2>
              <Button
                size="sm"
                kind="ghost"
                onClick={() => props.setIsEditorOpen(true)}
                renderIcon={SettingsAdjust}
                style={{ marginLeft: "auto" }}
              >
                Edit Schedule
              </Button>
              <IconButton align="left" kind="ghost" label="Close" size="sm" onClick={() => setIsOpen(false)}>
                <Close />
              </IconButton>
            </div>
            <p title={scheduleDescription} className={styles.detailsDescription}>
              {scheduleDescription}
            </p>
            <dl>
              <dt>Type</dt>
              <dd style={{ display: "flex", gap: "0.25rem", alignItems: "center" }}>
                {scheduleTypeLabelMap[schedule.type]}
                {schedule.type === "runOnce" ? <RepeatOne /> : <Repeat />}
              </dd>
            </dl>
            <dl>
              <dt>Status</dt>
              <dd style={{ display: "flex", gap: "0.25rem", alignItems: "center" }}>
                {scheduleStatusLabelMap[schedule.status]}
                {schedule.status === "inactive" ? (
                  <RadioButton className={styles.statusCircle} data-status={schedule.status} />
                ) : (
                  <CircleFilled className={styles.statusCircle} data-status={schedule.status} />
                )}
              </dd>
            </dl>
            {schedule.workflow && (
              <>
                <dl>
                  <dt>Workflow</dt>
                  <dd>{schedule.workflow.name}</dd>
                </dl>
              </>
            )}
            <dl>
              <dt>
                Scheduled Execution{" "}
                <TooltipHover
                  direction="top"
                  tooltipText={"The execution date is shown in local time based on the time zone of your browser."}
                  style={{ height: "0.75rem" }}
                >
                  <Information />
                </TooltipHover>
              </dt>
              <dd>{nextScheduledDate}</dd>
            </dl>
            <dl>
              <dt>Frequency </dt>
              <dd>
                {schedule.type === "runOnce"
                  ? "Run Once"
                  : schedule.cronSchedule
                  ? cronstrue.toString(schedule.cronSchedule)
                  : "---"}
              </dd>
            </dl>
            <dl>
              <dt>Labels</dt>
              <dd>{labels.length > 0 ? labels : "---"}</dd>
            </dl>
          </section>
          <section>
            <h2>Workflow Parameters</h2>
            <p style={{ marginBottom: "1rem" }}>Values for your workflow</p>
            <CodeSnippet light hideCopyButton type="multi">
              {schedule.params && schedule.params.length > 0
                ? JSON.stringify(schedule.params, null, 2)
                : "No parameters have been set on this Schedule."}
            </CodeSnippet>
          </section>
        </div>
      </>
    );
  }
  return (
    <>
      {isOpen && (
        <button
          aria-label="Close the panel"
          className={styles.overlay}
          data-testid="schedule-panel-overlay"
          onClick={() => setIsOpen(false)}
          tabIndex={-1}
          type="button"
        />
      )}
      <aside
        ref={panelRef}
        aria-hidden={!isOpen}
        aria-label={schedule ? `Schedule ${schedule.name}` : "Schedule"}
        aria-modal="true"
        className={cx(styles.panelContainer, props.className, { [styles.open]: isOpen })}
        role="dialog"
        tabIndex={-1}
      >
        {renderSchedule()}
      </aside>
    </>
  );
}
