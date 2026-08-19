import moment from "moment";
import { ApprovalStatus, RunStatus } from "Types";

const oneMonthAgo = moment().subtract(1, "month");
const monthAgoInDays = moment().diff(oneMonthAgo, "days");

const threeMonthsAgo = moment().subtract(3, "month");
const threeMonthsAgoInDays = moment().diff(threeMonthsAgo, "days");

// const sixMonthsAgo = moment().subtract(6, "month");
// const sixMonthsAgoInDays = moment().diff(sixMonthsAgo, "days");

// const oneYearAgo = moment().subtract(1, "year");
// const yearAgoInDays = moment().diff(oneYearAgo, "days");

export const timeframeOptions = [
  { label: "1 day", value: 1 },
  { label: "1 week", value: 7 },
  { label: "1 month", value: monthAgoInDays },
  { label: "3 months", value: threeMonthsAgoInDays },
  // { label: "6 months", value: sixMonthsAgoInDays },
  // { label: "1 year", value: yearAgoInDays }
];

export const executionOptions = [
  { label: "Scheduler", value: "scheduler" },
  { label: "Manual", value: "manual" },
  { label: "Webhook", value: "webhook" },
];

export const statusOptions: Array<{ label: string; value: RunStatus }> = [
  { label: "Succeeded", value: RunStatus.Succeeded },
  { label: "Failed", value: RunStatus.Failed },
  { label: "Running", value: RunStatus.Running },
  { label: "Cancelled", value: RunStatus.Cancelled },
  { label: "Invalid", value: RunStatus.Invalid },
  { label: "Waiting", value: RunStatus.Waiting },
  { label: "Ready", value: RunStatus.Ready },
  { label: "Not Started", value: RunStatus.NotStarted },
  { label: "Skipped", value: RunStatus.Skipped },
  { label: "Timed Out", value: RunStatus.TimedOut },
];

export const approvalStatusOptions = [
  { label: "Approved", value: ApprovalStatus.Approved },
  { label: "Rejected", value: ApprovalStatus.Rejected },
  { label: "Submitted", value: ApprovalStatus.Submitted },
];
