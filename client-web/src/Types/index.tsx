import { User } from "@boomerang-io/carbon-addons-boomerang-react";
import { Edge, EdgeProps, Node, NodeProps } from "reactflow";
import {
  EdgeExecutionCondition,
  FlowTeamStatus,
  NodeType,
  TokenType,
  UserRole,
  WorkflowEngineMode,
  WorkflowPropertyAction,
  WorkflowView,
} from "Constants";

/**
 * Take an object const and turn the values into a
 * union type e.g.
 * const ActionKind = {
 *    "Approval": "approval",
 *    "Manual": "manual"
 * } as const
 *
 * ActionKindType = ObjectValues<typeof ActionKind>
 * ^? "approval" | "manual"
 */
export type ObjectValues<T> = T[keyof T];

declare global {
  interface Window {
    _SERVER_DATA: {
      APP_ROOT: string;
      CORE_ENV_URL: string;
      CORE_SERVICE_ENV_URL: string;
      EMBEDDED_MODE: string;
      PRODUCT_ENV_URL: string;
      PRODUCT_SERVICE_ENV_URL: string;
      PRODUCT_STANDALONE: string;
      [key: string]: string;
    };
  }
}

export enum PlatformRole {
  Admin = "admin",
  User = "user",
  Operator = "operator",
}

export enum UserStatus {
  Active = "active",
  Inactive = "inactive",
}

export interface FlowUser extends User {
  id: string;
  email: string;
  name: string;
  displayName?: string;
  type: PlatformRole;
  creationDate: string;
  lastLoginDate: string;
  status: UserStatus;
  labels?: Record<string, string>;
  settings?: FlowUserSettings;
}

export interface FlowUserSettings {
  hasConsented: boolean;
  isShowHelp: boolean | null;
  ifFirstVisit: boolean;
}

export interface SimpleApprover {
  approverId: string;
  approverEmail: string;
  approverName: string;
  comments: string;
  date: string;
  approved: boolean;
}

export interface Action {
  id: string;
  taskRunRef: string;
  workflowRunRef: string;
  workflowRef: string;
  teamRef: string;
  status: string;
  type: string;
  creationDate: string;
  taskName: string;
  workflowName: string;
  teamName: string;
  numberOfApprovals: number;
  approvalsRequired: number;
  actioners: SimpleApprover[];
  instructions: any;
}

export interface Approver {
  name: string;
  id: string;
  email: string;
}

export interface ApproverGroup {
  id: string;
  name: string;
  creationDate: string;
  approvers: Array<Member>;
}

export interface DataDrivenInput {
  id: string;
  default: string | Array<string> | Array<{ key: string; value: string }> | Object;
  defaultValue: string | Array<string> | Array<{ key: string; value: string }> | Object;
  description?: string;
  helperText?: string;
  language?: string;
  name: string;
  key?: string; //TODO: remove - for backwards compatability with other param layers
  disabled?: boolean;
  label?: string;
  onChange?: (args: any) => void;
  onBlur?: (args: any) => void;
  options?: Array<{ key: string; value: string }>;
  placeholder?: string;
  readOnly?: boolean;
  required?: boolean;
  value: string | Array<string> | Array<{ key: string; value: string }> | Object;
  type: string;
  min?: number;
  max?: number;
}

export interface ResultParameter {
  name: string;
  description: string;
  value?: any;
}

export interface ModalTriggerProps {
  openModal(): void;
}

export interface FormikSetFieldValue {
  (id: string, value: string | Array<string> | boolean | undefined): void;
}

export interface CreateWorkflowSummary {
  name: string;
  displayName: string;
  description: string;
  icon: string;
}

export interface WorkflowWorkspace {
  name: string;
  type: string;
  optional: boolean;
  spec?: {
    accessMode?: string;
    className?: string;
    size?: number;
    mountPath?: string;
  };
}

export interface Workflow {
  // id: string;
  name: string;
  displayName: string;
  creationDate: string;
  status: WorkflowStatus;
  timeout: number;
  retries: number;
  version: number;
  description: string;
  icon: string;
  labels?: Record<string, string>;
  annotations?: Record<string, object>;
  markdown?: string;
  params?: Array<DataDrivenInput>;
  tasks: Array<any>; //TODO: what should this type be
  changelog: {
    author: string;
    reason: string;
    date: string;
  };
  triggers: {
    event: WorkflowTrigger;
    github: WorkflowTrigger;
    manual: WorkflowTrigger;
    schedule: WorkflowTrigger;
    webhook: WorkflowTrigger;
  };
  upgradesAvailable: boolean;
  workspaces: Array<WorkflowWorkspace>;
}

export enum WorkflowStatus {
  Active = "active",
  Inactive = "inactive",
}

export interface WorkflowTrigger {
  enabled: boolean;
  conditions: Array<WorkflowTriggerCondition>;
}

export enum WorkflowTriggerType {
  Manual = "manual",
  Scheduler = "scheduler",
  Event = "event",
  Webhook = "webhook",
  GitHub = "github",
}

export interface WorkflowTriggerCondition {
  operation: WorkflowTriggerConditionOperation;
  field: string;
  value: string;
  values: Array<string>;
}

export enum WorkflowTriggerConditionOperation {
  Matches = "matches",
  Equals = "equals",
  In = "in",
}

export type WorkflowViewType = ObjectValues<typeof WorkflowView>;
export type EdgeExecutionConditionType = ObjectValues<typeof EdgeExecutionCondition>;

type PageableSort = {
  sorted: boolean;
  empty: boolean;
  unsorted: boolean;
};

type Pageable<T> = {
  empty: boolean;
  first: boolean;
  last: boolean;
  number: number;
  size: number;
  totalPages: number;
  totalElements: number;
  numberOfElements: number;
  sort: PageableSort;
  content: Array<T>;
};

export type PaginatedUserResponse = Pageable<FlowUser>;
export type PaginatedTeamResponse = Pageable<FlowTeam>;
export type PaginatedTaskResponse = Pageable<Task>;
export type PaginatedWorkflowResponse = Pageable<Workflow>;
export type PaginatedSchedulesResponse = Pageable<ScheduleUnion>;

export type WorkflowNodeData = {
  name: string;
  taskRef: string;
  taskVersion: number;
  upgradesAvailable: boolean;
  params: Array<{ name: string; value: string }>;
  results: Array<{ name: string; description: string }>;
};
export type WorkflowNode = Node<WorkflowNodeData>;
export type WorkflowNodeProps = NodeProps<WorkflowNodeData>;

export type WorkflowEdgeData = {
  decisionCondition: string;
  executionCondition: EdgeExecutionConditionType;
};
export type WorkflowEdge = Edge<WorkflowEdgeData>;
export type WorkflowEdgeProps = EdgeProps<WorkflowEdgeData>;

export interface WorkflowParameter {
  defaultValue: string;
  description: string;
  key: string;
  label: string;
  required: boolean;
  type: string;
}

export interface WorkflowCanvas extends Workflow {
  edges: Array<WorkflowEdge>;
  nodes: Array<Node<WorkflowNodeData>>;
  config?: Array<DataDrivenInput>;
}

export enum ApprovalStatus {
  Approved = "approved",
  Rejected = "rejected",
  Submitted = "submitted",
}

export interface ChangeLogEntry {
  date: string;
  reason: string;
  author: string;
  version: number;
}

export type ChangeLog = Array<ChangeLogEntry>;

export interface Task {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  status: string;
  category: string;
  version: number;
  creationDate: string;
  labels?: Record<string, string>;
  icon: string;
  type: string;
  changelog: ChangeLog;
  verified: boolean;
  spec: TaskSpec;
}

export interface TaskSpec {
  arguments?: Array<string>;
  command?: Array<string>;
  params?: Array<DataDrivenInput>;
  envs?: any;
  image?: string;
  results?: Array<{ name: string; description: string }>;
  script?: number;
  workingDir?: string;
}

export type FlowTeamStatusType = ObjectValues<typeof FlowTeamStatus>;

export interface FlowTeam {
  name: string;
  displayName: string;
  description?: string;
  creationDate: string;
  status: FlowTeamStatusType;
  externalRef?: string;
  labels?: Record<string, string>;
  quotas: FlowTeamQuotas;
  members: Array<Member>;
  settings?: unknown;
  parameters: Array<DataDrivenInput>;
  approverGroups: Array<ApproverGroup>;
}

// As part of the Profile
export interface FlowTeamSummary {
  name: string;
  displayName: string;
  description?: string;
  creationDate: string;
  status: ObjectValues<typeof FlowTeamStatus>;
  externalRef?: string;
  labels?: Record<string, string>;
  insights: {
    workflows: number;
    members: number;
  };
}

export interface FlowTeamQuotas {
  currentRuns: number;
  currentWorkflowCount: number;
  currentConcurrentRuns: number;
  currentRunTotalDuration: number;
  currentRunMedianDuration: number;
  currentWorkflowStorage: number;
  currentWorkflowRunStorage: number;
  maxWorkflowCount: number;
  maxWorkflowRunMonthly: number;
  maxWorkflowStorage: number;
  maxWorkflowRunStorage: number;
  maxWorkflowRunDuration: number;
  maxConcurrentRuns: number;
  monthlyResetDate: string;
}

export interface PaginatedSort {
  direction: string;
  property: string;
  ignoreCase: boolean;
  nullHandling: string;
  descending: boolean;
  ascending: boolean;
}

export interface PaginatedResponse<RecordType> {
  totalPages: number;
  totalElements: number;
  last: boolean;
  sort: Array<PaginatedSort>;
  first: boolean;
  numberOfElements: number;
  size: number;
  number: number;
  content: Array<RecordType>;
}

export interface Member {
  id?: string;
  email?: string;
  role?: MemberRole;
}

export enum MemberRole {
  Owner = "owner",
  Editor = "editor",
  Reader = "reader",
}

export interface Property {
  value: string | null;
  readOnly: boolean;
  id: string;
  description: string;
  key: string;
  label: string;
  type: string;
}

export interface PatchProperty {
  value?: string;
  readOnly?: boolean;
  id?: string;
  description?: string;
  key?: string;
  label?: string;
  type?: string;
}

export type TokenScopeType = ObjectValues<typeof TokenType>;

export interface Token {
  id: string;
  name: string;
  type: TokenScopeType;
  creationDate: string;
  expirationDate: string;
  principal: string;
  description: string;
  valid: boolean;
  permissions: Array<{
    scope: TokenScopeType;
    principal: string;
    actions: Array<string>;
  }>;
}

export interface TokenRequest {
  expiryDate: string | number | null;
  description: string;
}

export interface ComboBoxItem {
  name: string;
  label?: string;
  value: string;
}

export interface WorkflowTemplate {
  name: string;
  displayName: string;
  icon: string;
  description: string;
  creationDate: string;
  markdown: string;
  version: Number;
  labels?: Record<string, string>;
  annotations?: Record<string, object>;
  params?: Array<DataDrivenInput>;
  tasks: Array<any>; //TODO: what should this be
  changelog: {
    author: string;
    reason: string;
    date: string;
  };
  config: Array<DataDrivenInput>;
}

export enum RunStatus {
  NotStarted = "notstarted",
  Ready = "ready",
  Running = "running",
  Waiting = "waiting",
  Succeeded = "succeeded",
  Failed = "failed",
  Invalid = "invalid",
  Cancelled = "cancelled",
  Skipped = "skipped",
  TimedOut = "timedout",
}

export enum RunPhase {
  Pending = "pending",
  Running = "running",
  Completed = "completed",
  Finalized = "finalized",
}

export interface FlowNavigationItemChild {
  activeClassName?: string;
  element?: React.ReactNode;
  onClick?: (e: React.SyntheticEvent) => any;
  href?: string;
  disabled?: boolean;
  large: boolean;
  link: string;
  name: string;
  renderIcon: SVGElement;
  to?: string;
}

export interface FlowNavigationItem {
  disabled: boolean;
  icon: string;
  name: string;
  link: string;
  type: "link" | "menu" | "divider";
  childLinks: Array<FlowNavigationItemChild>;
  beta?: boolean;
}

export type PlatformFeatureKey =
  | "consent.enabled"
  | "docs.enabled"
  | "metering.enabled"
  | "notifications.enabled"
  | "support.enabled"
  | "welcome.enabled";

export interface ContextConfig {
  features: {
    [k in PlatformFeatureKey]: boolean;
  };
  navigation: Array<{ name: string; url: string }>;
  platform: {
    appName?: string;
    baseEnvUrl: string;
    baseServicesUrl: string;
    communityUrl?: string;
    displayLogo: boolean;
    name: string;
    platformName: string;
    privateTeams: boolean;
    sendMail: boolean;
    signOutUrl: string;
    version: string;
  };
  platformMessage: {
    kind: string;
    message: string;
    title: string;
  };
}

export type FlowFeatureKey =
  | "activity"
  | "enable.verified.tasks.edit"
  | "global.parameters"
  | "insights"
  | "team.management"
  | "team.parameters"
  | "team.tasks"
  | "user.management"
  | "team.quotas"
  | "workflow.tokens"
  | "workflow.triggers";

export interface FlowFeatures {
  features: {
    [k in FlowFeatureKey]: boolean;
  };
}

export interface Integration {
  id: string;
  name: string;
  description: string;
  icon: string;
  instructions: string;
  link: string;
  status: string;
}

//Schedule types
export type ScheduleStatus = "active" | "inactive" | "deleted" | "trigger_disabled" | "error";
export type ScheduleType = "runOnce" | "cron" | "advancedCron";

export interface Schedule {
  id: string;
  name: string;
  description?: string;
  labels?: Record<string, string>;
  nextScheduleDate: string;
  params?: Array<Param>;
  status: ScheduleStatus;
  type: ScheduleType;
  timezone: string;
  workflowRef: string;
  workflow?: Workflow;
}

export interface ScheduleDate extends Schedule {
  dateSchedule: string;
  type: "runOnce";
}

export interface ScheduleCron extends Schedule {
  cronSchedule: string;
  type: "cron" | "advancedCron";
}

export type ScheduleUnion = ScheduleDate | ScheduleCron;

export interface CalendarEntry {
  scheduleId: string;
  dates: Array<string>;
}

export interface CalendarEvent {
  start: Date;
  end: Date;
  title: string;
  resource: ScheduleUnion;
  onClick?: () => void;
}

export type CalendarDateRange = { start: string | Date; end: string | Date } | Date[];

export interface ScheduleManagerFormInputs {
  advancedCron: boolean;
  cronSchedule: string;
  dateTime: string;
  days: Array<DayOfWeekKey>;
  description: string;
  id: string;
  labels: Array<string>;
  name: string;
  type: ScheduleType;
  timezone: { label: string; value: string };
  time: string;
  workflowRef: string;
  [key: string]: any;
}

export type DayOfWeekKey = "monday" | "tuesday" | "wednesday" | "thursday" | "friday" | "saturday" | "sunday";
export type DayOfWeekCronAbbreviation = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";

export type MultiSelectItem = {
  label: string;
  value: string;
};

export interface MultiSelectItems<Type = MultiSelectItem> {
  selectedItems: Array<Type>;
}

export type WorkflowEditorState = WorkflowCanvas & { hasUnsavedUpdates?: boolean };
export type WorkflowEngineModeType = ObjectValues<typeof WorkflowEngineMode>;
export type WorkflowPropertyActionType = ObjectValues<typeof WorkflowPropertyAction>;
export type UserRoleType = ObjectValues<typeof UserRole>;
export type NodeTypeType = ObjectValues<typeof NodeType>;

export interface ConfigureWorkflowFormValues {
  params: Workflow["params"];
  description: string;
  icon: string;
  labels: Array<{ key: string; value: string }>;
  name: string;
  displayName: string;
  storage: {
    workflowrun: {
      enabled: boolean;
      size: number;
      mountPath: string;
    };
    workflow: {
      enabled: boolean;
      size: number;
      mountPath: string;
    };
  };
  retries: number;
  timeout: number;
  triggers: Workflow["triggers"];
}

export interface WorkflowRun {
  annotations: RunAnnototations;
  awaitingApproval: boolean;
  creationDate: string;
  duration: number;
  id: string;
  initiatedByRef: string;
  labels: Record<string, string>;
  params: Array<Param>;
  phase: string;
  results: Array<RunResult>;
  retries: number;
  startTime: string;
  status: RunStatus;
  statusMessage: string;
  tasks: Array<TaskRun>;
  timeout: number;
  trigger: string;
  workspaces: Array<WorkflowWorkspace>;
  workflowName: string;
  workflowRef: string;
  workflowRevisionRef: string;
  workflowVersion: number;
}

export interface TaskRun {
  annotations: TaskAnnotations;
  creationDate: string;
  duration: number;
  id: string;
  labels: Record<string, string>;
  name: string;
  params: Array<Param>;
  phase: RunPhase;
  results: Array<RunResult>;
  retries: number;
  spec: Spec;
  startTime: string;
  status: RunStatus;
  statusMessage: string;
  taskRef: string;
  timeout: number;
  type: string;
  workflowRef: string;
  workflowRevisionRef: string;
  workflowRunRef: string;
  workflowName: string;
  workspaces: Array<WorkflowWorkspace>;
}

export interface RunAnnototations {
  "boomerang.io/task-deletion": string;
  "boomerang.io/task-default-image": string;
  "boomerang.io/team-name": string;
  "boomerang.io/kind": string;
  "boomerang.io/generation": string;
}

export interface Param {
  name: string;
  value: string;
}

export interface TaskAnnotations {
  "boomerang.io/position": BoomerangIoPosition;
  "boomerang.io/team-name"?: string;
  "boomerang.io/kind"?: string;
  "boomerang.io/generation"?: string;
}

export interface BoomerangIoPosition {
  x: number;
  y: number;
}

export interface Spec {
  arguments: string[] | null;
  command: any[] | null;
  debug: boolean;
  deletion: null | string;
  envs: null;
  image: null | string;
  timeout: number;
  script: null;
  workingDir: null;
}

export interface RunResult {
  name: string;
  description: string;
  value: string;
}
