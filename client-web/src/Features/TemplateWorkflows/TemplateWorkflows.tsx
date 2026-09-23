import queryString from "query-string";
import { useLoaderData, useNavigate, useLocation } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import ErrorDragon from "Components/ErrorDragon";
import WorkflowTemplateCard from "Components/WorkflowTemplateCard";
import WorkflowsHeader from "Components/WorkflowsHeader";
import { WorkflowView } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { PaginatedWorkflowResponse, Workflow } from "Types";
import styles from "./TemplateWorkflows.module.scss";

// Workflow Templates are seeded, read-only content - the loader seeds them and a v3 upgrade
// imports them, so this screen only browses. A Workflow is created from a template on the Home
// screen (Components/WorkflowTemplateHomeCard).
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
          subtitle="Reuseable Workflows available to all workspaces. Start one from the Home screen."
          handleUpdateFilter={handleUpdateFilter}
          searchQuery={searchQuery}
          workflowList={workflows}
          viewType={WorkflowView.Template}
        />
        <div aria-label="Workspace Workflows" className={styles.content} role="region">
          <section className={styles.sectionContainer}>
            <RenderTemplates
              errorLoading={errorLoading}
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
  filteredWorkflows: Workflow[];
  searchQuery: string | string[] | null;
};

// No `isLoading` branch: the loader resolves before this component renders (see
// GlobalParameters.tsx for the same "isLoading={false}, errorLoading flag instead" shift away
// from react-query's fetch-state trio).
const RenderTemplates = ({ errorLoading, filteredWorkflows, searchQuery }: TemplatesProps) => {
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
    </div>
  );
};
