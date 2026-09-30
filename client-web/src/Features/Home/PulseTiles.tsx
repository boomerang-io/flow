import React from "react";
import { Link } from "react-router-dom";
import { ArrowRight } from "@carbon/react/icons";
import cx from "classnames";
import moment from "moment";
import { appLink } from "Config/appConfig";
import { count } from "./copy";
import { HomeLoaderData } from "./homeLoader";
import styles from "./pulseTiles.module.scss";

interface PulseTilesProps {
  data: HomeLoaderData;
  /** The workspace the "Runs today" and "Waiting on you" tiles open when nothing more specific applies. */
  primaryWorkspace: string;
  workspaceCount: number;
  workflowCount: number;
  memberCount: number;
  activityEnabled: boolean;
  schedulesEnabled: boolean;
}

interface TileProps {
  to: string;
  label: string;
  value: React.ReactNode;
  detail: React.ReactNode;
  accent?: boolean;
  testId: string;
}

function Tile({ to, label, value, detail, accent = false, testId }: TileProps) {
  return (
    <Link to={to} className={cx(styles.tile, { [styles.tileAccent]: accent })} data-testid={testId}>
      <span>
        <span className={styles.label}>{label}</span>
        <span className={styles.value}>{value}</span>
      </span>
      <span className={styles.footer}>
        <span className={styles.detail}>{detail}</span>
        <ArrowRight size={20} className={styles.arrow} aria-hidden="true" />
      </span>
    </Link>
  );
}

/**
 * The four numbers a returning user lands on. Each tile is a link into the feature that owns the
 * number, so the page never becomes a dashboard of its own.
 */
export default function PulseTiles(props: PulseTilesProps) {
  const { data, primaryWorkspace, workspaceCount, workflowCount, memberCount, activityEnabled, schedulesEnabled } = props;
  const { runsToday, attention, attentionTotal, attentionApprovals, attentionManual, nextSchedule, schedulesTotal } = data;
  const attentionWorkspace = attention[0]?.workspace ?? primaryWorkspace;

  return (
    <nav aria-label="Today" className={styles.grid}>
      <Tile
        to={activityEnabled ? appLink.activity({ workspace: primaryWorkspace }) : "#recent-activity"}
        label="Runs today"
        value={runsToday.all}
        testId="home-pulse-runs"
        detail={
          <>
            <span className={styles.legend}>
              <span className={cx(styles.dot, styles.dotSucceeded)} aria-hidden="true" />
              {runsToday.succeeded} succeeded
            </span>
            <span className={styles.legend}>
              <span className={cx(styles.dot, styles.dotFailed)} aria-hidden="true" />
              {runsToday.failed} failed
            </span>
            <span className={styles.legend}>
              <span className={cx(styles.dot, styles.dotRunning)} aria-hidden="true" />
              {runsToday.running} running
            </span>
          </>
        }
      />
      <Tile
        to={appLink.actions({ workspace: attentionWorkspace })}
        label="Waiting on you"
        value={attentionTotal}
        accent={attentionTotal > 0}
        testId="home-pulse-attention"
        detail={`${count(attentionApprovals, "approval")} · ${count(attentionManual, "manual task")}`}
      />
      {schedulesEnabled ? (
        <Tile
          to={appLink.schedules({ workspace: nextSchedule?.workspace ?? primaryWorkspace })}
          label="Next scheduled run"
          value={nextSchedule ? moment(nextSchedule.nextScheduleDate).fromNow() : "None"}
          testId="home-pulse-schedule"
          detail={
            nextSchedule
              ? `${nextSchedule.name} · ${nextSchedule.workspaceDisplayName} · ${moment(nextSchedule.nextScheduleDate).format("HH:mm")}`
              : count(schedulesTotal, "active schedule")
          }
        />
      ) : null}
      <Tile
        to="#your-workspaces"
        label="Your workspaces"
        value={workspaceCount}
        testId="home-pulse-workspaces"
        detail={`${count(workflowCount, "workflow")} · ${count(memberCount, "member")}`}
      />
    </nav>
  );
}
