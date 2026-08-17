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

import { FlowTeam } from "Types";

interface TeamDetailedHeaderProps {
  team: FlowTeam;
}

function TeamDetailedHeader({ team }: TeamDetailedHeaderProps) {
  const location: any = useLocation();
  const isActive = team.status === "active";

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
          <p>{team.displayName}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    ) : (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{team.displayName}</p>
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
            <HeaderTitle>Manage Team</HeaderTitle>
            <HeaderSubtitle>Team Owners & Administrators can manage this team.</HeaderSubtitle>
          </div>
          {team && (
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
                  <dd className={styles.dataValue}>{moment(team.creationDate).format("YYYY-MM-DD")}</dd>
                </dl>
              </section>
            </div>
          )}
        </div>
      }
      footer={
        <Tabs ariaLabel="Team pages">
          <Tab
            exact
            label="Members"
            to={{ pathname: appLink.manageTeam({ team: team.name }), state: location.state }}
          />
          <Tab
            exact
            label="Workflows"
            to={{ pathname: appLink.manageTeamWorkflows({ team: team.name }), state: location.state }}
          />
          <Tab
            exact
            label="Approver Groups"
            to={{ pathname: appLink.manageTeamApprovers({ team: team.name }), state: location.state }}
          />
          <Tab
            exact
            label="Quotas"
            to={{ pathname: appLink.manageTeamQuotas({ team: team.name }), state: location.state }}
          />
          <Tab
            exact
            label="Tokens"
            to={{ pathname: appLink.manageTeamTokens({ team: team.name }), state: location.state }}
          />
          <Tab
            exact
            label="Settings"
            to={{ pathname: appLink.manageTeamSettings({ team: team.name }), state: location.state }}
          />
        </Tabs>
      }
    />
  );
}

export default TeamDetailedHeader;
