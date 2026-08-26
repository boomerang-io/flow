import queryString from "query-string";
import { useLoaderData, useNavigate, useLocation } from "react-router-dom";
import { formatErrorMessage } from "@boomerang-io/utils";
import CreateWorkflowTemplate from "Components/CreateWorkflowTemplate";
import EmptyState from "Components/EmptyState";
import ErrorDragon from "Components/ErrorDragon";
import WorkflowTemplateCard from "Components/WorkflowTemplateCard";
import WorkflowsHeader from "Components/WorkflowsHeader";
import { WorkflowView, HttpMethod } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { PaginatedWorkflowResponse, Workflow } from "Types";
import styles from "./TemplateWorkflows.module.scss";

// Workflow Templates are static content served read-only from backend resources (not a managed
// entity with its own CRUD lifecycle) - the read here is a simple list fetch, same shape as
// before, just moved server-side. See Features/Parameters/GlobalParameters/GlobalParameters.tsx
// for the reference conversion this follows.
type LoaderData = {
  templates: PaginatedWorkflowResponse | null;
  errorLoading: boolean;
};

export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get(serviceUrl.template.getWorkflowTemplates());
    return { templates: response.data, errorLoading: false };
  } catch (error) {
    return { templates: null, errorLoading: true };
  }
}

// Single action, keyed by intent, for both remaining writes against Workflow Templates: import
// (create) from CreateWorkflowTemplate.tsx and delete from WorkflowTemplateCard.tsx. Both of
// those components render as descendants of this route's element (no nested <Route>), so their
// `useFetcher()` calls resolve to this action without needing an explicit `action` path - same
// as a <Form> with no action defaults to the closest route in context.
type ActionResult = {
  ok: boolean;
  intent: "create" | "delete";
  name?: string;
  errorMessage?: { title: string; message: string };
};

export async function action({ request }: { request: Request }): Promise<ActionResult> {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "delete") {
    const name = String(formData.get("name"));
    try {
      await serverFetch(request).delete(serviceUrl.template.getWorkflowTemplate({ name }));
      return { ok: true, intent: "delete", name };
    } catch (error) {
      return {
        ok: false,
        intent: "delete",
        name,
        errorMessage: formatErrorMessage({ error, defaultMessage: "Delete Workflow Template Failed" }),
      };
    }
  }

  const workflow = JSON.parse(String(formData.get("workflow")));
  try {
    const response = await serverFetch(request)({
      url: serviceUrl.template.postWorkflowTemplate(),
      data: workflow,
      method: HttpMethod.Post,
    });
    return { ok: true, intent: "create", name: response.data.name };
  } catch (error) {
    return {
      ok: false,
      intent: "create",
      name: workflow.name,
      errorMessage: formatErrorMessage({ error, defaultMessage: "Import Template Failed" }),
    };
  }
}

export default function TemplateWorkflows() {
  const { templates, errorLoading } = useLoaderData() as LoaderData;
  const navigate = useNavigate();
  const location = useLocation();
  let { query: searchQuery = "" } = queryString.parse(location.search, {
    arrayFormat: "comma",
  });
  const workflows = templates?.content ?? [];

  let safeQuery = "";
  if (Array.isArray(searchQuery)) {
    safeQuery = searchQuery.join().toLowerCase();
  } else if (searchQuery) {
    safeQuery = searchQuery.toLowerCase();
  }

  const handleUpdateFilter = (query: { [key: string]: any }) => {
    const queryStr = `?${queryString.stringify(
      { ...queryString.parse(location.search, { arrayFormat: "comma" }), ...query },
      { arrayFormat: "comma", skipEmptyString: true },
    )}`;

    navigate({ search: queryStr });
  };

  const filteredWorkflows = workflows.filter((workflow) => workflow.name.toLowerCase().includes(safeQuery));

  return (
    <>
      <div className={styles.container}>
        <WorkflowsHeader
          title="Workflow Templates"
          subtitle="Define reuseable Workflows available to all workspaces as Templates."
          handleUpdateFilter={handleUpdateFilter}
          searchQuery={searchQuery}
          workflowList={workflows}
          viewType={WorkflowView.Template}
        />
        <div aria-label="Workspace Workflows" className={styles.content} role="region">
          <section className={styles.sectionContainer}>
            <RenderTemplates
              errorLoading={errorLoading}
              workflows={workflows}
              filteredWorkflows={filteredWorkflows}
              searchQuery={searchQuery}
            />
          </section>
        </div>
      </div>
    </>
  );
}

type TemplatesProps = {
  errorLoading: boolean;
  workflows: Workflow[];
  filteredWorkflows: Workflow[];
  searchQuery: string | string[] | null;
};

// No `isLoading` branch: the loader resolves before this component renders (see
// GlobalParameters.tsx for the same "isLoading={false}, errorLoading flag instead" shift away
// from react-query's fetch-state trio).
const RenderTemplates = ({ errorLoading, workflows, filteredWorkflows, searchQuery }: TemplatesProps) => {
  if (errorLoading) {
    return <ErrorDragon />;
  }

  if (!filteredWorkflows || (filteredWorkflows?.length === 0 && searchQuery !== "")) {
    return <EmptyState />;
  }
  return (
    <div className={styles.workflows}>
      {filteredWorkflows.map((workflow) => (
        <WorkflowTemplateCard key={workflow.name} workflow={workflow} />
      ))}
      {<CreateWorkflowTemplate workflows={workflows} />}
    </div>
  );
};
