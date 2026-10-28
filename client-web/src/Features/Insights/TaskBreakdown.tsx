import React from "react";
import { Link } from "react-router-dom";
import { Dropdown, Layer, Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@carbon/react";
import { TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { appLink } from "Config/appConfig";
import { formatDuration } from "Utils/dateHelper";
import type { InsightsTasks } from "Types";
import styles from "./Insights.module.scss";

type Option = { id: string; label: string; groupBy?: string };

// The server's labels: on an ai task run its model, on a for-each child run's task runs its item.
const MODEL_LABEL = "label:boomerang.io/model";
const ITEM_LABEL = "label:boomerang.io/item";

// The API's groupBy forms, keyed by option; a label of your own also needs its key.
const groupOptions: Array<Option> = [
  { id: "task", label: "Task", groupBy: "task" },
  { id: "type", label: "Task type", groupBy: "type" },
  { id: "workflow", label: "Workflow", groupBy: "workflow" },
  { id: "model", label: "Model", groupBy: MODEL_LABEL },
  { id: "item", label: "For-each item", groupBy: ITEM_LABEL },
  { id: "label", label: "Label" },
];

// The option a groupBy value shows as: a fixed one, else a label of your own.
function optionOf(groupBy: string | null) {
  if (!groupBy) {
    return "";
  }
  return groupOptions.find((option) => option.groupBy === groupBy)?.id ?? "label";
}

const bucketOptions: Array<Option> = [
  { id: "", label: "Whole period" },
  { id: "hour", label: "Hour" },
  { id: "day", label: "Day" },
  { id: "month", label: "Month" },
];

const bucketFormat: Record<string, string> = { hour: "YYYY-MM-DD HH:00", day: "YYYY-MM-DD", month: "YYYY-MM" };

const costFormat = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 4 });

/** The task-view params the page keeps in its URL. */
export interface TaskView {
  /** As the API takes it: task, type, workflow or label:<key>. */
  groupBy: string | null;
  bucket: string | null;
  /** The run label whose values each measure a wall clock. */
  spanBy: string | null;
}

interface TaskBreakdownProps {
  workspace: string;
  view: TaskView;
  tasks: InsightsTasks | null;
  error: boolean;
  /** Merges task-view params into the page URL, keeping every other filter. */
  update: (params: Record<string, string | undefined>) => void;
}

function NameInput(props: { id: string; labelText: string; value: string; apply: (value: string) => void }) {
  const [text, setText] = React.useState(props.value);
  const apply = () => {
    if (text.trim() !== props.value) {
      props.apply(text.trim());
    }
  };
  return (
    <TextInput
      id={props.id}
      labelText={props.labelText}
      value={text}
      onChange={(event: React.ChangeEvent<HTMLInputElement>) => setText(event.target.value)}
      onBlur={apply}
      onKeyDown={(event: React.KeyboardEvent<HTMLInputElement>) => {
        if (event.key === "Enter") {
          apply();
        }
      }}
    />
  );
}

/**
 * The period's task runs grouped by task, type, workflow, a param or a run label, optionally by
 * hour, day or month, with the slowest of them. Read only once a grouping is chosen.
 */
export default function TaskBreakdown({ workspace, view, tasks, error, update }: TaskBreakdownProps) {
  const groupKind = optionOf(view.groupBy);
  const labelKey = groupKind === "label" ? (view.groupBy ?? "").replace(/^label:/, "") : "";
  // A label picked before its key is typed stays local until the key is applied.
  const [kind, setKind] = React.useState(groupKind);
  React.useEffect(() => setKind(groupKind), [groupKind]);
  const spanKey = view.spanBy?.replace(/^label:/, "") ?? "";
  const keyHeader = groupKind === "label" ? labelKey : groupOptions.find((option) => option.id === groupKind)?.label;
  const hasUsage = Boolean(tasks?.rows.some((row) => row.aiTaskRuns));

  function chooseGroup({ selectedItem }: { selectedItem: Option | null }) {
    setKind(selectedItem?.id ?? "");
    if (selectedItem?.groupBy) {
      update({ taskGroupBy: selectedItem.groupBy });
    }
  }

  let body: React.ReactNode;
  if (!view.groupBy && !view.spanBy) {
    body = <p className={styles.empty}>Choose how to group the period's task runs.</p>;
  } else if (error || !tasks) {
    body = <p className={styles.empty}>The task runs could not be loaded.</p>;
  } else if (tasks.rows.length === 0) {
    body = <p className={styles.empty}>No completed task runs in this period.</p>;
  } else {
    body = (
      <>
        <Table size="md" aria-label="Task run statistics" useZebraStyles={false}>
          <TableHead>
            <TableRow>
              {tasks.bucket ? <TableHeader>Time (UTC)</TableHeader> : null}
              <TableHeader>{keyHeader ?? "Task runs"}</TableHeader>
              <TableHeader className={styles.numeric}>Runs</TableHeader>
              <TableHeader className={styles.numeric}>Failed</TableHeader>
              <TableHeader>Mean</TableHeader>
              <TableHeader>p50</TableHeader>
              <TableHeader>p95</TableHeader>
              <TableHeader>Max</TableHeader>
              {tasks.spanBy ? (
                <>
                  <TableHeader className={styles.numeric}>Spans</TableHeader>
                  <TableHeader>Span p50</TableHeader>
                  <TableHeader>Span p95</TableHeader>
                </>
              ) : null}
              {hasUsage ? (
                <>
                  <TableHeader className={styles.numeric}>Tokens</TableHeader>
                  <TableHeader className={styles.numeric}>Cost</TableHeader>
                </>
              ) : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {tasks.rows.map((row) => (
              <TableRow key={`${row.bucket ?? ""}|${row.key ?? ""}`} data-testid="insights-task-group">
                {tasks.bucket ? (
                  <TableCell>{row.bucket ? moment.utc(row.bucket).format(bucketFormat[tasks.bucket]) : ""}</TableCell>
                ) : null}
                <TableCell>
                  {row.key ?? <span className={styles.muted}>{tasks.groupBy ? "(none)" : "All task runs"}</span>}
                </TableCell>
                <TableCell className={styles.numeric}>{row.taskRuns}</TableCell>
                <TableCell className={styles.numeric}>{row.failed}</TableCell>
                <TableCell>{formatDuration(row.meanDuration)}</TableCell>
                <TableCell>{formatDuration(row.p50Duration)}</TableCell>
                <TableCell>{formatDuration(row.p95Duration)}</TableCell>
                <TableCell>{formatDuration(row.maxDuration)}</TableCell>
                {tasks.spanBy ? (
                  <>
                    <TableCell className={styles.numeric}>{row.spans ?? 0}</TableCell>
                    <TableCell>{formatDuration(row.p50Span ?? 0)}</TableCell>
                    <TableCell>{formatDuration(row.p95Span ?? 0)}</TableCell>
                  </>
                ) : null}
                {hasUsage ? (
                  <>
                    <TableCell className={styles.numeric}>{row.totalTokens?.toLocaleString() ?? ""}</TableCell>
                    <TableCell className={styles.numeric}>
                      {row.cost !== undefined ? costFormat.format(row.cost) : ""}
                    </TableCell>
                  </>
                ) : null}
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {tasks.truncated ? (
          <p className={styles.panelNote}>Only the first 1,000 groups are shown; narrow the filters or the period.</p>
        ) : null}
        {tasks.slowest && tasks.slowest.length > 0 ? (
          <div data-testid="insights-slowest-tasks">
            <h3 className={styles.panelSubtitle}>Slowest task runs</h3>
            <ul className={styles.failureList}>
              {tasks.slowest.map((slow) => (
                <li key={slow.taskRunRef} className={styles.failureRow}>
                  <span className={styles.failureCount}>{formatDuration(slow.duration)}</span>
                  <span className={styles.failureBody}>
                    <strong>{slow.name}</strong>
                    <span className={styles.muted}>{slow.status}</span>
                  </span>
                  <Link to={appLink.execution({ workspace, runId: slow.workflowRunRef })} className={styles.failureLink}>
                    {slow.startTime ? moment(slow.startTime).fromNow() : "open run"}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </>
    );
  }

  return (
    <section className={styles.panel} aria-labelledby="insights-tasks-title" data-testid="insights-tasks">
      <div className={styles.panelHeader}>
        <h2 id="insights-tasks-title" className={styles.panelTitle}>
          Task runs
        </h2>
        <span className={styles.panelHint}>
          Durations over succeeded task runs · tokens and cost over ai task runs · a for-each task counts its items ·
          workflow, label and date filters apply
        </span>
      </div>
      {/* A layer, so the fields take the next layer's colour and stand out on the white panel. */}
      <Layer className={styles.taskControls}>
        <Dropdown
          id="insights-tasks-group"
          titleText="Group by"
          label="Choose a grouping"
          items={groupOptions}
          itemToString={(item: Option | null) => (item ? item.label : "")}
          selectedItem={groupOptions.find((option) => option.id === kind) ?? null}
          onChange={chooseGroup}
        />
        {kind === "label" ? (
          <NameInput
            key={labelKey}
            id="insights-tasks-group-name"
            labelText="Label key"
            value={labelKey}
            apply={(key) => update({ taskGroupBy: key ? `label:${key}` : undefined })}
          />
        ) : null}
        <Dropdown
          id="insights-tasks-bucket"
          titleText="By time"
          label="Whole period"
          items={bucketOptions}
          itemToString={(item: Option | null) => (item ? item.label : "")}
          selectedItem={bucketOptions.find((option) => option.id === (view.bucket ?? "")) ?? bucketOptions[0]}
          onChange={({ selectedItem }: { selectedItem: Option | null }) => update({ taskBucket: selectedItem?.id || undefined })}
        />
        <NameInput
          id="insights-tasks-span"
          labelText="Wall clock by run label"
          value={spanKey}
          apply={(key) => update({ taskSpanBy: key ? `label:${key}` : undefined })}
        />
      </Layer>
      {body}
    </section>
  );
}
