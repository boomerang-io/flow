import { Loading } from "@carbon/react";
import queryString from "query-string";
import { useHistory, useLocation } from "react-router-dom";
import CreateWorkflowTemplate from "Components/CreateWorkflowTemplate";
import EmptyState from "Components/EmptyState";
import ErrorDragon from "Components/ErrorDragon";
import WorkflowTemplateCard from "Components/WorkflowTemplateCard";
import WorkflowsHeader from "Components/WorkflowsHeader";
import { useQuery } from "Hooks";
import { WorkflowView } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import { Workflow } from "Types";
import styles from "./TemplateWorkflows.module.scss";

export default function TemplateWorkflows() {
  const history = useHistory();
  const location = useLocation();
  let { query: searchQuery = "" } = queryString.parse(location.search, {
    arrayFormat: "comma",
  });
  const {
    data: templatesWorkflowData,
    error: errorTemplatesWorkflow,
    isLoading: isLoadingTemplatesWorkflow,
  } = useQuery(serviceUrl.template.getWorkflowTemplates());

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

    history.push({ search: queryStr });
  };

  const filteredWorkflows =
    templatesWorkflowData?.content?.filter((workflow: any) => workflow.name.toLowerCase().includes(safeQuery)) ?? [];

  return (
    <>
      <div className={styles.container}>
        <WorkflowsHeader
          title="Workflow Templates"
          subtitle="Define reuseable Workflows available to all teams as Templates."
          handleUpdateFilter={handleUpdateFilter}
          searchQuery={searchQuery}
          workflowList={templatesWorkflowData?.content ? templatesWorkflowData.content : []}
          viewType={WorkflowView.Template}
        />
        <div aria-label="Team Workflows" className={styles.content} role="region">
          <section className={styles.sectionContainer}>
            <RenderTemplates
              isLoading={isLoadingTemplatesWorkflow}
              error={errorTemplatesWorkflow}
              workflows={templatesWorkflowData?.content ? templatesWorkflowData.content : []}
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
  isLoading: boolean;
  error: any;
  workflows: Workflow[];
  filteredWorkflows: Workflow[];
  searchQuery: string | string[] | null;
};

const RenderTemplates = ({ isLoading, error, workflows, filteredWorkflows, searchQuery }: TemplatesProps) => {
  if (isLoading) {
    return <Loading />;
  }

  if (error) {
    return <ErrorDragon />;
  }

  if (!filteredWorkflows || (filteredWorkflows?.length === 0 && searchQuery !== "")) {
    return <EmptyState />;
  }
  return (
    <div className={styles.workflows}>
      {filteredWorkflows.map((workflow) => (
        <WorkflowTemplateCard
          key={workflow.name}
          workflow={workflow}
          quotas={null}
          viewType={WorkflowView.Template}
          getWorkflowsUrl={serviceUrl.template.getWorkflowTemplates()}
        />
      ))}
      {<CreateWorkflowTemplate workflows={workflows} />}
    </div>
  );
};
