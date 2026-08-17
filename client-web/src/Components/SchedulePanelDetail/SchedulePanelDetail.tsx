import React from "react";
import { Button, CodeSnippet } from "@carbon/react";
import { TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import SlidingPane from "react-sliding-pane";
import cronstrue from "cronstrue";
import cx from "classnames";
import moment from "moment-timezone";
import { DATETIME_LOCAL_DISPLAY_FORMAT } from "Utils/dateHelper";
import { scheduleStatusLabelMap, scheduleTypeLabelMap } from "Constants";
import { CircleFilled, Information, SettingsAdjust, RadioButton, Repeat, RepeatOne } from "@carbon/react/icons";
import { ScheduleUnion } from "Types";
import "react-sliding-pane/dist/react-sliding-pane.css";
import styles from "./SchedulePanelDetail.module.scss";

interface SchedulePanelDetailProps {
  className: string;
  event?: ScheduleUnion;
  isOpen: boolean;
  setIsOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
}

export default function SchedulePanelDetail(props: SchedulePanelDetailProps) {
  const schedule = props.event;

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

    const labels = [];
    //TODO: fix labels
    // for (const entry of schedule?.labels || []) {
    //   labels.push(
    //     <Tag key={entry.key} style={{ marginLeft: 0 }} type="teal">
    //       {entry.key}:{entry.value}
    //     </Tag>
    //   );
    // }

    return (
      <>
        <div className={styles.detailsContainer}>
          <section className={styles.detailsInfo}>
            <div className={styles.detailsTitle}>
              <h2 title={schedule.name}>{schedule.name}</h2>
              <Button
                size="sm"
                kind="ghost"
                onClick={props.setIsEditorOpen}
                renderIcon={SettingsAdjust}
                style={{ marginLeft: "auto" }}
              >
                Edit Schedule
              </Button>
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
              {schedule?.params
                ? JSON.stringify(schedule?.params, null, 2)
                : "No parameters have been set on this Schedule."}
            </CodeSnippet>
          </section>
          <hr />
        </div>
      </>
    );
  }
  return (
    <SlidingPane
      hideHeader
      className={cx(styles.panelContainer, props.className)}
      isOpen={props.isOpen}
      onRequestClose={() => props.setIsOpen(false)}
      width="32rem"
    >
      {renderSchedule()}
    </SlidingPane>
  );
}
