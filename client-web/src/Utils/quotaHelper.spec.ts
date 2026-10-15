import { formatQuotaLimit, formatQuotaUsage, isQuotaLimited, quotaUsagePercent } from "./quotaHelper";

describe("quotaHelper", () => {
  it("treats 0 as unlimited and a positive number as the limit", () => {
    expect(isQuotaLimited(0)).toBe(false);
    expect(isQuotaLimited(10)).toBe(true);
    expect(isQuotaLimited(undefined)).toBe(false);
  });

  it("formats a limit with its unit and 0 as Unlimited", () => {
    expect(formatQuotaLimit(10, "Workflows")).toBe("10 Workflows");
    expect(formatQuotaLimit(0, "Workflows")).toBe("Unlimited");
  });

  it("formats usage against the limit", () => {
    expect(formatQuotaUsage(3, 10)).toBe("3 of 10");
    expect(formatQuotaUsage(3, 0)).toBe("3 of unlimited");
  });

  it("measures usage only against a limit, capped at 100", () => {
    expect(quotaUsagePercent(3, 10)).toBe(30);
    expect(quotaUsagePercent(15, 10)).toBe(100);
    expect(quotaUsagePercent(3, 0)).toBeNull();
  });
});
