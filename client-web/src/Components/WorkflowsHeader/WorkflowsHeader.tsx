import React from "react";
import { Layer, Search, Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Link } from "react-router-dom";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { FlowWorkspace, WorkflowViewType, Workflow } from "Types";
import styles from "./workflowsHeader.module.scss";

interface WorkflowsHeaderProps {
  pretitle?: React.ReactNode;
  title: string;
  subtitle: string;
  handleUpdateFilter: (args: { query: string }) => void;
  searchQuery: string | string[] | null;
  workspace?: FlowWorkspace | null;
  workflowList: Array<Workflow>;
  viewType: WorkflowViewType;
}

const WorkflowsHeader: React.FC<WorkflowsHeaderProps> = ({
  pretitle,
  title,
  subtitle,
  handleUpdateFilter,
  searchQuery,
  workspace,
  workflowList,
  viewType,
}) => {
  const workflowsCount = workflowList.length;
  const workflowsCountStr = workflowsCount > 0 ? `(${workflowsCount})` : "";

  const handleOnSearchInputChange = (e: { target: HTMLInputElement; type: "change" }) => {
    handleUpdateFilter({ query: e.target?.value ?? "" });
  };

  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{workspace ? workspace.displayName : "---"}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  return (
    <Header
      className={styles.container}
      includeBorder={false}
      nav={viewType === WorkflowView.Workflow ? <NavigationComponent /> : null}
      header={
        <>
          {Boolean(pretitle) ? <HeaderSubtitle>{pretitle}</HeaderSubtitle> : null}
          <HeaderTitle>{`${title} ${workflowsCountStr}`}</HeaderTitle>
          {Boolean(subtitle) ? <HeaderSubtitle className={styles.headerMessage}>{subtitle}</HeaderSubtitle> : null}
        </>
      }
      actions={
        <ActionsBar>
          <Layer className={styles.search}>
            <Search
              disabled={!workflowsCount || workflowsCount === 0}
              data-testid="workflows-workspace-search"
              id="search-workspace-workflows"
              labelText={`Search for a ${viewType}`}
              onChange={handleOnSearchInputChange}
              placeholder={`Search for a ${viewType}`}
              value={Array.isArray(searchQuery) ? searchQuery[0] ?? "" : searchQuery ?? ""}
            />
          </Layer>
        </ActionsBar>
      }
    />
  );
};

export default WorkflowsHeader;

interface ActionsBarProps {
  children: React.ReactNode;
}

const ActionsBar: React.FC<ActionsBarProps> = (props: ActionsBarProps) => {
  return <div className={styles.filterContainer}>{props.children}</div>;
};
