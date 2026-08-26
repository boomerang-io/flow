import React from "react";
import { Button } from "@carbon/react";
import { WarningAlt } from "@carbon/react/icons";
import { ComposedModal, Error, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import axios from "axios";
import { useFeature } from "flagged";
import { matchSorter } from "match-sorter";
import queryString from "query-string";
import { useLoaderData, useNavigate, useLocation } from "react-router-dom";
import CreateWorkflow from "Components/CreateWorkflow";
import EmptyState from "Components/EmptyState";
import WorkflowCard from "Components/WorkflowCard";
import WorkflowsHeader from "Components/WorkflowsHeader";
import { useWorkspaceContext } from "Hooks";
import { WorkflowView } from "Constants";
import { FeatureFlag, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowWorkspace, ModalTriggerProps, Workflow, WorkflowRun } from "Types";
import WorkflowQuotaModalContent from "./WorkflowQuotaModalContent";
import styles from "./workflows.module.scss";

// Route module: this file's `loader`/`action` are attached to the route in app/routes/workflows.tsx
// rather than being defined inline there, so the data-fetching code stays next to the component
// that consumes it - see Features/Parameters/GlobalParameters/GlobalParameters.tsx for the
// reference conversion this follows.
//
// The route is workspace-scoped (`/:workspace/workflows`), so the loader/action read the
// `:workspace` route param directly rather than reaching for useWorkspaceContext (client-only) -
// see Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx for the same pattern.
type LoaderData = {
  workflows: Array<Workflow>;
  errorLoading: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  try {
    const response = await serverFetch(request).get(
      serviceUrl.workspace.workflow.getWorkflows({ workspace, query: "statuses=active,inactive" }),
    );
    return { workflows: response.data.content, errorLoading: false };
  } catch (error) {
    return { workflows: [], errorLoading: true };
  }
}

// Single action, keyed by intent, for every write this route's card/create/update components make
// (WorkflowCard.tsx: delete/duplicate/execute; Components/CreateWorkflow/CreateWorkflow.tsx:
// create/import; WorkflowCard/UpdateWorkflow/UpdateWorkflow.tsx: update). All of those components
// render as descendants of this route's element (no nested <Route>), so their `useFetcher()`
// calls resolve to this action without an explicit path - same as WorkflowTemplateCard.tsx /
// CreateWorkflowTemplate.tsx do against TemplateWorkflows.tsx's action.
//
// Field convention: "workflow" always carries a JSON-stringified payload (a CreateWorkflowSummary,
// a full Workflow, or an execute body); "workflowName" always carries a bare identifier string.
type ActionResult =
  | { ok: true; intent: "create" | "import"; workflow: Workflow }
  | { ok: false; intent: "create" | "import"; errorMessage: { title: string; message: string } }
  | { ok: true; intent: "delete" | "duplicate" | "update" }
  | { ok: false; intent: "delete" | "duplicate" | "update" }
  | { ok: true; intent: "execute"; execution: WorkflowRun; redirect: boolean }
  | { ok: false; intent: "execute"; errorMessage: { title: string; message: string } };

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ActionResult> {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "delete") {
    const workflowName = String(formData.get("workflowName"));
    try {
      await serverFetch(request).delete(serviceUrl.workspace.workflow.getWorkflow({ workspace, workflow: workflowName }));
      return { ok: true, intent: "delete" };
    } catch (error) {
      return { ok: false, intent: "delete" };
    }
  }

  if (intent === "duplicate") {
    const workflowName = String(formData.get("workflowName"));
    try {
      await serverFetch(request).post(
        serviceUrl.workspace.workflow.postDuplicateWorkflow({ workspace, workflow: workflowName }),
      );
      return { ok: true, intent: "duplicate" };
    } catch (error) {
      return { ok: false, intent: "duplicate" };
    }
  }

  if (intent === "execute") {
    const workflowName = String(formData.get("workflowName"));
    const body = JSON.parse(String(formData.get("body")));
    const redirect = formData.get("redirect") === "true";
    try {
      const response = await serverFetch(request).post(
        serviceUrl.workspace.workflow.postSubmitWorkflow({ workspace, workflow: workflowName }),
        body,
      );
      return { ok: true, intent: "execute", execution: response.data, redirect };
    } catch (error) {
      // Mirrors the previous client-side mutateAsync catch: a 429 (quota exceeded) gets its own
      // message built from the response body, everything else falls back to formatErrorMessage.
      const response = axios.isAxiosError(error) ? error.response : undefined;
      if (response?.status === 429) {
        const data = response.data;
        return {
          ok: false,
          intent: "execute",
          errorMessage: {
            title: "Quota Exceeded",
            message:
              data && typeof data === "object" && "message" in data
                ? String(data.message)
                : "Too many requests. Please try again later.",
          },
        };
      }
      return {
        ok: false,
        intent: "execute",
        errorMessage: formatErrorMessage({ error, defaultMessage: "Run Workflow Failed" }),
      };
    }
  }

  if (intent === "update") {
    const workflow = JSON.parse(String(formData.get("workflow")));
    try {
      await serverFetch(request).put(serviceUrl.workspace.workflow.putApplyWorkflow({ workspace }), workflow);
      return { ok: true, intent: "update" };
    } catch (error) {
      return { ok: false, intent: "update" };
    }
  }

  // "create" | "import" - both post the same payload shape (a CreateWorkflowSummary for create, a
  // full Workflow for import) to the same endpoint; only the resulting notification differs, which
  // the component derives from `intent`. viewType mirrors the two branches CreateWorkflow.tsx has
  // always supported (Workflow vs Template) even though only Workflow is exercised by this route
  // today (Components/CreateWorkflow is only ever rendered here) - kept for parity rather than
  // dropped, since narrowing it isn't this conversion's call to make.
  const viewType = String(formData.get("viewType"));
  const workflow = JSON.parse(String(formData.get("workflow")));
  try {
    const response =
      viewType === WorkflowView.Template
        ? await serverFetch(request).post(serviceUrl.template.postWorkflowTemplate(), workflow)
        : await serverFetch(request).post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace }), workflow);
    return { ok: true, intent: intent === "import" ? "import" : "create", workflow: response.data };
  } catch (error) {
    return {
      ok: false,
      intent: intent === "import" ? "import" : "create",
      errorMessage: formatErrorMessage({
        error,
        defaultMessage: `${intent === "import" ? "Import" : "Create"} ${viewType} Failed`,
      }),
    };
  }
}

export default function Workflows() {
  const { workflows, errorLoading } = useLoaderData() as LoaderData;
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const location = useLocation();

  /** Check if there is an active workspace or redirect to home */
  if (!workspace) {
    navigate(appLink.home());
    return null;
  }

  const { query: searchQuery = "" } = queryString.parse(location.search, {
    arrayFormat: "comma",
  });

  const handleUpdateFilter = (args: { query: string }) => {
    const queryStr = `?${queryString.stringify(args, { arrayFormat: "comma", skipEmptyString: true })}`;
    navigate({ search: queryStr });
  };

  let safeSearchQuery = "";
  if (Array.isArray(searchQuery)) {
    safeSearchQuery = searchQuery.join().toLowerCase();
  } else if (searchQuery) {
    safeSearchQuery = searchQuery.toLowerCase();
  }

  if (errorLoading) {
    return (
      <Layout workspace={workspace} handleUpdateFilter={handleUpdateFilter} searchQuery={safeSearchQuery} workflowList={[]}>
        <Error />
      </Layout>
    );
  }

  return (
    <Layout workspace={workspace} handleUpdateFilter={handleUpdateFilter} searchQuery={safeSearchQuery} workflowList={workflows}>
      <WorkflowContent workspace={workspace} searchQuery={safeSearchQuery} workflowList={workflows} />
    </Layout>
  );
}

interface LayoutProps {
  workspace: FlowWorkspace;
  children: React.ReactNode;
  handleUpdateFilter: (args: { query: string }) => void;
  searchQuery: string;
  workflowList: Array<Workflow>;
}

function Layout(props: LayoutProps) {
  return (
    <div className={styles.container}>
      <WorkflowsHeader
        title={"Workflows"}
        subtitle="Your playground to create, execute, and collaborate on workflows. Work smarter with automation."
        handleUpdateFilter={props.handleUpdateFilter}
        searchQuery={props.searchQuery}
        workspace={props.workspace}
        workflowList={props.workflowList}
        viewType={WorkflowView.Workflow}
      />
      <div aria-label="My Workflows" className={styles.content} role="region" id="my-workflows">
        <section className={styles.sectionContainer}>{props.children}</section>
      </div>
    </div>
  );
}

interface WorkflowContentProps {
  workspace: FlowWorkspace;
  searchQuery: string;
  workflowList: Array<Workflow>;
}

const WorkflowContent: React.FC<WorkflowContentProps> = ({ workspace, searchQuery, workflowList }) => {
  const hasWorkflows = workflowList.length > 0;
  const workspaceQuotasEnabled = useFeature(FeatureFlag.WorkspaceQuotasEnabled);
  const hasReachedWorkflowLimit = workspace.quotas.maxWorkflowCount <= workspace.quotas.currentWorkflowCount;

  const filteredWorkflowList = Boolean(searchQuery)
    ? matchSorter(workflowList, searchQuery, { keys: ["name"] })
    : workflowList;

  if (hasWorkflows && Boolean(searchQuery) && filteredWorkflowList.length === 0) {
    return <EmptyState />;
  }

  return (
    <>
      <hgroup className={styles.header}>
        {workspaceQuotasEnabled ? (
          <div className={styles.workspaceQuotaContainer}>
            <div className={styles.quotaDescriptionContainer}>
              <p
                className={styles.workspaceQuotaText}
              >{`Workflow quota - ${workspace.quotas.currentWorkflowCount} of ${workspace.quotas.maxWorkflowCount} used`}</p>
              {hasReachedWorkflowLimit && (
                <TooltipHover
                  direction="top"
                  tooltipText={
                    "This workspace has reached the maximum number of Workflows allowed. Contact your administrator or workspace owner to increase the quota, or delete a Workflow to create a new one."
                  }
                >
                  <WarningAlt className={styles.warningIcon} />
                </TooltipHover>
              )}
            </div>
            <ComposedModal
              composedModalProps={{
                containerClassName: styles.quotaModalContainer,
                shouldCloseOnOverlayClick: true,
              }}
              modalHeaderProps={{
                title: `Workspace quotas - ${workspace.displayName}`,
                subtitle:
                  "Quotas are set by the administrator. If you have a concern about your allotted amounts, contact an administrator.",
              }}
              modalTrigger={({ openModal }: ModalTriggerProps) => (
                <Button iconDescription="View quota details" kind="ghost" size="sm" onClick={openModal}>
                  View more quotas
                </Button>
              )}
            >
              {({ closeModal }) => <WorkflowQuotaModalContent closeModal={closeModal} quotas={workspace.quotas} />}
            </ComposedModal>
          </div>
        ) : null}

        {hasWorkflows === false ? (
          <p className={styles.noWorkflowsMessage}>
            This workspace doesn’t have any Workflows - be the first to take the plunge.
          </p>
        ) : null}
      </hgroup>
      <div className={styles.workflows}>
        {filteredWorkflowList.map((workflow) => (
          <WorkflowCard
            key={workflow.name}
            quotas={workspace.quotas}
            workspaceName={workspace.name}
            viewType={WorkflowView.Workflow}
            workflow={workflow}
          />
        ))}
        <CreateWorkflow
          hasReachedWorkflowLimit={hasReachedWorkflowLimit}
          workspace={workspace}
          viewType={WorkflowView.Workflow}
          workflows={workflowList}
        />
      </div>
    </>
  );
};
