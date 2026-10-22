/** "a=b, c=d" typed in the label filter, as the list the API takes; blank entries dropped. */
export function parseLabels(text: string): Array<string> {
  return text
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
}

/** "1,234,567"; tokens are counts, so grouped digits read better than an abbreviation. */
export function formatTokens(tokens: number | null | undefined): string {
  return (tokens ?? 0).toLocaleString("en-US");
}

/**
 * "12.3456"; cost is the endpoint's own figure in its own currency, so no symbol is shown.
 * Small amounts keep four decimals; "n/a" when nothing reported a cost.
 */
export function formatCost(cost: number | null | undefined): string {
  if (cost === null || cost === undefined) {
    return "n/a";
  }
  return cost.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** "87.4%"; "n/a" when nothing finished with an outcome. */
export function formatPercent(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? "n/a" : `${(rate * 100).toFixed(1)}%`;
}

export interface Delta {
  /** "31%", "4.1 points" */
  text: string;
  direction: "up" | "down" | "flat";
  /** Whether the change is welcome: a rising success rate is good, a rising p95 is bad. */
  tone: "good" | "bad" | "neutral";
}

function direction(change: number): Delta["direction"] {
  return change > 0 ? "up" : change < 0 ? "down" : "flat";
}

function tone(change: number, higherIsBetter: boolean | null): Delta["tone"] {
  if (change === 0 || higherIsBetter === null) {
    return "neutral";
  }
  return (change > 0) === higherIsBetter ? "good" : "bad";
}

/** Relative change of a count or duration; null when there is nothing to compare with. */
export function relativeDelta(current: number, previous: number, higherIsBetter: boolean | null): Delta | null {
  if (!previous) {
    return null;
  }
  const change = (current - previous) / previous;
  return { text: `${Math.abs(Math.round(change * 100))}%`, direction: direction(change), tone: tone(change, higherIsBetter) };
}

/** Change of a rate in percentage points; null when either period has no rate. */
export function pointsDelta(current: number | null, previous: number | null): Delta | null {
  if (current === null || previous === null) {
    return null;
  }
  const change = (current - previous) * 100;
  const points = Math.abs(change).toFixed(1);
  return { text: `${points} ${points === "1.0" ? "point" : "points"}`, direction: direction(change), tone: tone(change, true) };
}

/** "61% scheduled · 27% webhook · 12% manual", largest share first. */
export function triggerMix(byTrigger: Record<string, number>): string {
  const total = Object.values(byTrigger).reduce((sum, count) => sum + count, 0);
  if (total === 0) {
    return "";
  }
  return Object.entries(byTrigger)
    .sort(([, a], [, b]) => b - a)
    .slice(0, 3)
    .map(([trigger, count]) => `${Math.round((count / total) * 100)}% ${trigger}`)
    .join(" · ");
}
