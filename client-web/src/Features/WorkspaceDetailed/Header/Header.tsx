import React from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Checkmark, Close } from "@carbon/react/icons";
import moment from "moment";
import { Link, useLocation } from "react-router-dom";
import { appLink } from "Config/appConfig";

import styles from "./Header.module.scss";

import { FlowWorkspace } from "Types";

interface WorkspaceDetailedHeaderProps {
  workspace: FlowWorkspace;
}

function WorkspaceDetailedHeader({ workspace }: WorkspaceDetailedHeaderProps) {
  const location: any = useLocation();
  const isActive = workspace.status === "active";

  const navList = location?.state?.navList;

  const NavigationComponent = () => {
    return Boolean(navList) ? (
      <Breadcrumb noTrailingSlash>
        {navList.map((navItem: any) => {
          return (
            <BreadcrumbItem>
              <Link to={navItem.to}>{navItem.text}</Link>
            </BreadcrumbItem>
          );
        })}
        <BreadcrumbItem isCurrentPage>
          <p>{workspace.displayName}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    ) : (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{workspace.displayName}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  return (
    <Header
      includeBorder
      className={styles.container}
      nav={<NavigationComponent />}
      header={
        <div className={styles.infoContainer}>
          <div>
            <HeaderTitle>Manage Workspace</HeaderTitle>
            <HeaderSubtitle>Workspace Owners & Administrators can manage this workspace.</HeaderSubtitle>
          </div>
          {workspace && (
            <div className={styles.infoDetailsContainer}>
              <section className={styles.subHeaderContainer}>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Status</dt>
                  <dd className={styles.dataValue}>
                    <div className={styles.status}>
                      {isActive ? <Checkmark style={{ fill: "#009d9a" }} /> : <Close style={{ fill: "#da1e28" }} />}
                      <p className={styles.statusText}>{isActive ? "Active" : "Inactive"}</p>
                    </div>
                  </dd>
                </dl>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Date Created</dt>
                  <dd className={styles.dataValue}>{moment(workspace.creationDate).format("YYYY-MM-DD")}</dd>
                </dl>
              </section>
            </div>
          )}
        </div>
      }
      footer={
        <Tabs ariaLabel="Workspace pages">
          <Tab
            exact
            label="Members"
            to={{ pathname: appLink.manageWorkspace({ workspace: workspace.name }), state: location.state }}
          />
          <Tab
            exact
            label="Workflows"
            to={{ pathname: appLink.manageWorkspaceWorkflows({ workspace: workspace.name }), state: location.state }}
          />
          <Tab
            exact
            label="Approver Groups"
            to={{ pathname: appLink.manageWorkspaceApprovers({ workspace: workspace.name }), state: location.state }}
          />
          <Tab
            exact
            label="Quotas"
            to={{ pathname: appLink.manageWorkspaceQuotas({ workspace: workspace.name }), state: location.state }}
          />
          <Tab
            exact
            label="Tokens"
            to={{ pathname: appLink.manageWorkspaceTokens({ workspace: workspace.name }), state: location.state }}
          />
          <Tab
            exact
            label="Settings"
            to={{ pathname: appLink.manageWorkspaceSettings({ workspace: workspace.name }), state: location.state }}
          />
        </Tabs>
      }
    />
  );
}

export default WorkspaceDetailedHeader;
