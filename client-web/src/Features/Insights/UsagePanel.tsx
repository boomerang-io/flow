import React from "react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow, Tile } from "@carbon/react";
import { InsightsUsage } from "Types";
import { formatCost, formatTokens } from "./format";
import styles from "./Insights.module.scss";

interface UsagePanelProps {
  /** The panel's own box by default; a detail passes its section style. */
  className?: string;
  /** Distinguishes the summary's panel from the detail's when both render. */
  id: string;
  title: string;
  usage: InsightsUsage;
}

function Figure({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <Tile className={styles.headlineTile}>
      <span className={styles.headlineLabel}>{label}</span>
      <span className={styles.headlineValue}>{value}</span>
      <span className={styles.headlineDetail}>{detail}</span>
    </Tile>
  );
}

/** Model usage summed from the results the ai task runs reported: totals, then one row per model. */
export default function UsagePanel({ id, title, usage, className }: UsagePanelProps) {
  const hasCost = usage.cost !== null;
  const titleId = `${id}-title`;
  const extra = [
    usage.reasoningTokens > 0 ? `${formatTokens(usage.reasoningTokens)} reasoning` : null,
    usage.cachedPromptTokens > 0 ? `${formatTokens(usage.cachedPromptTokens)} cached prompt` : null,
  ].filter(Boolean);

  return (
    <section className={className ?? styles.panel} aria-labelledby={titleId} data-testid={id}>
      <div className={styles.panelHeader}>
        <h2 id={titleId} className={styles.panelTitle}>
          {title}
        </h2>
        <span className={styles.panelHint}>Summed from the results ai task runs reported</span>
      </div>
      <div className={styles.headline}>
        <Figure
          label="Total tokens"
          value={formatTokens(usage.totalTokens)}
          detail={`${formatTokens(usage.promptTokens)} prompt · ${formatTokens(usage.completionTokens)} completion`}
        />
        {hasCost ? (
          <Figure label="Cost" value={formatCost(usage.cost)} detail="As the endpoints reported it, in their currency" />
        ) : null}
        <Figure
          label="AI task runs"
          value={formatTokens(usage.aiTaskRuns)}
          detail={extra.length > 0 ? extra.join(" · ") : "Each for-each item counts once"}
        />
      </div>
      <Table size="sm" aria-label={`${title} by model`} useZebraStyles={false}>
        <TableHead>
          <TableRow>
            <TableHeader>Model</TableHeader>
            <TableHeader className={styles.numeric}>Task runs</TableHeader>
            <TableHeader className={styles.numeric}>Prompt</TableHeader>
            <TableHeader className={styles.numeric}>Completion</TableHeader>
            <TableHeader className={styles.numeric}>Total</TableHeader>
            {hasCost ? <TableHeader className={styles.numeric}>Cost</TableHeader> : null}
          </TableRow>
        </TableHead>
        <TableBody>
          {usage.byModel.map((model) => (
            <TableRow key={model.model ?? "unreported"}>
              <TableCell>{model.model ?? <span className={styles.muted}>Not reported</span>}</TableCell>
              <TableCell className={styles.numeric}>{formatTokens(model.aiTaskRuns)}</TableCell>
              <TableCell className={styles.numeric}>{formatTokens(model.promptTokens)}</TableCell>
              <TableCell className={styles.numeric}>{formatTokens(model.completionTokens)}</TableCell>
              <TableCell className={styles.numeric}>{formatTokens(model.totalTokens)}</TableCell>
              {hasCost ? <TableCell className={styles.numeric}>{formatCost(model.cost)}</TableCell> : null}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
  );
}
