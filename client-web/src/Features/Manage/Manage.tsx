import React from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { Link, Navigate, Outlet } from "react-router-dom";
import { AppPath, appLink, FeatureFlag } from "Config/appConfig";
import { ProtectedRoute } from "Features/App/App";
import { useRoutePermissions } from "Features/App/AppRoutes";
import styles from "./manage.module.scss";

export interface ManageTab {
  label: string;
  to: string;
  allowed: boolean;
}

/**
 * The Manage tabs in display order, each with whether this caller may see it: the same grants
 * the tab's route checks, and the same feature flags the server's navigation applies.
 */
export function useManageTabs(): Array<ManageTab> {
  const permissions = useRoutePermissions();
  const workspaceManagementEnabled = Boolean(useFeature(FeatureFlag.WorkspaceManagementEnabled));
  const userManagementEnabled = Boolean(useFeature(FeatureFlag.UserManagementEnabled));
  const globalParametersEnabled = Boolean(useFeature(FeatureFlag.GlobalParametersEnabled));
  return [
    { label: "Settings", to: AppPath.Settings, allowed: permissions.canReadSettings },
    { label: "Workspaces", to: AppPath.WorkspaceList, allowed: workspaceManagementEnabled && permissions.canReadWorkspaces },
    { label: "Users", to: AppPath.UserList, allowed: userManagementEnabled && permissions.canReadUsers },
    { label: "Parameters", to: AppPath.Properties, allowed: globalParametersEnabled && permissions.canReadParameters },
    { label: "Tokens", to: AppPath.Tokens, allowed: permissions.canReadTokens },
    { label: "Tasks", to: AppPath.Tasks, allowed: permissions.canReadTasks },
    { label: "Audit", to: AppPath.Audit, allowed: permissions.canReadAudit },
  ];
}

/** "/admin": the first tab the caller may see, or the same 403 every guarded route shows. */
export function ManageIndex() {
  const first = useManageTabs().find((tab) => tab.allowed);
  if (!first) {
    return <ProtectedRoute allowed={false}>{null}</ProtectedRoute>;
  }
  return <Navigate to={first.to} replace />;
}

/**
 * The Manage area's frame: breadcrumb, title and the tab row, with the tab's own page below.
 * A page with tabs carries no description line; each tab's content starts with its controls.
 */
export default function Manage() {
  const permissions = useRoutePermissions();
  const tabs = useManageTabs().filter((tab) => tab.allowed);

  return (
    <div className={styles.container}>
      <Helmet>
        <title>Manage</title>
      </Helmet>
      <Header
        includeBorder
        className={styles.header}
        nav={
          <Breadcrumb noTrailingSlash>
            <BreadcrumbItem>
              <Link to={appLink.home()}>Home</Link>
            </BreadcrumbItem>
            <BreadcrumbItem isCurrentPage>
              <p>Manage</p>
            </BreadcrumbItem>
          </Breadcrumb>
        }
        header={<HeaderTitle>Manage</HeaderTitle>}
        footer={
          <Tabs ariaLabel="Manage pages">
            {tabs.map((tab) => (
              <Tab key={tab.label} label={tab.label} to={tab.to} />
            ))}
          </Tabs>
        }
      />
      <div className={styles.content}>
        <Outlet context={permissions} />
      </div>
    </div>
  );
}
