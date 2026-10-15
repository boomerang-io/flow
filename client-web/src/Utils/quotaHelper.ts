// A workspace quota read from the API has two states: 0 is unlimited, a positive number is the
// limit. (An absent stored value was already resolved to the platform default server-side.)

export function isQuotaLimited(quota: number | null | undefined): boolean {
  return typeof quota === "number" && quota > 0;
}

/** "Unlimited", or the limit with its unit: `formatQuotaLimit(10, "Workflows")` is "10 Workflows". */
export function formatQuotaLimit(quota: number | null | undefined, unit: string): string {
  return isQuotaLimited(quota) ? `${quota} ${unit}`.trim() : "Unlimited";
}

/** Usage against the limit: "3 of 10", or "3 of unlimited". */
export function formatQuotaUsage(current: number, quota: number | null | undefined): string {
  return `${current} of ${isQuotaLimited(quota) ? quota : "unlimited"}`;
}

/** Usage as a percentage capped at 100, or null for an unlimited quota - there is nothing to measure against. */
export function quotaUsagePercent(current: number, quota: number | null | undefined): number | null {
  if (!isQuotaLimited(quota)) {
    return null;
  }
  return Math.min((current / (quota as number)) * 100, 100);
}
