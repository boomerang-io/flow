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
import { appLink, FeatureFlag } from "Config/appConfig";
import { ProtectedRoute } from "Features/App/App";
import { useRoutePermissions } from "Features/App/AppRoutes";
import { manageTabs, type ManageTab } from "./manageTabs";
import styles from "./manage.module.scss";

export type { ManageTab };

/** The Manage tabs for this caller, from the route permissions and the feature flags. */
export function useManageTabs(): Array<ManageTab> {
  const permissions = useRoutePermissions();
  return manageTabs(permissions, {
    workspaceManagement: Boolean(useFeature(FeatureFlag.WorkspaceManagementEnabled)),
    userManagement: Boolean(useFeature(FeatureFlag.UserManagementEnabled)),
    globalParameters: Boolean(useFeature(FeatureFlag.GlobalParametersEnabled)),
  });
}

/**
 * "/admin" when its loader did not redirect: the first tab the caller may see (a client-side
 * fallback for a failed profile read), or the same 403 every guarded route shows.
 */
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
