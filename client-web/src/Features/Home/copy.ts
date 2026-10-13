/** "1 run", "2 runs" - the plural is the singular plus "s" unless given. */
export function count(n: number, singular: string, plural = `${singular}s`): string {
  return `${n} ${n === 1 ? singular : plural}`;
}

/** The hero's one-sentence summary of the day, built from the loader's rollup. */
export function daySummary(args: {
  runsToday: number;
  workspaces: number;
  attentionTotal: number;
  attentionApprovals: number;
  attentionManual: number;
}): string {
  const { runsToday, workspaces, attentionTotal, attentionApprovals, attentionManual } = args;
  const where = `across your ${count(workspaces, "workspace")}`;
  const runs = runsToday === 0 ? `No runs yet today ${where}.` : `${count(runsToday, "run")} today ${where}.`;
  if (attentionTotal === 0) {
    return `${runs} Nothing is waiting on you.`;
  }
  const verb = attentionTotal === 1 ? "is" : "are";
  if (attentionApprovals + attentionManual === attentionTotal) {
    const parts = [
      attentionApprovals > 0 ? count(attentionApprovals, "approval") : null,
      attentionManual > 0 ? count(attentionManual, "manual task") : null,
    ].filter(Boolean);
    return `${runs} ${parts.join(" and ")} ${verb} waiting for you.`;
  }
  return `${runs} ${count(attentionTotal, "action")} ${verb} waiting for you.`;
}
