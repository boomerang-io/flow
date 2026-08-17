import React from "react";
import {
  FeatureSideNav as SideNav,
  FeatureSideNavLink as SideNavLink,
  FeatureSideNavLinks as SideNavLinks,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import styles from "./navPanel.module.scss";

interface NavPanelProps {
  team: string;
  workflowRef: string;
}

const NavPanel: React.FC<NavPanelProps> = ({ team, workflowRef }) => {
  // List of Nav Items
  const navigationItems = [
    {
      name: "General",
      path: `${appLink.editorConfigureGeneral({
        team: team,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Triggers",
      path: `${appLink.editorConfigureTriggers({
        team: team,
        workflow: workflowRef,
      })}`,
    },
    // {
    //   name: "Parameters",
    //   path: `${appLink.editorConfigureParams({
    //     team: team,
    //     workflow: workflowRef,
    //   })}`,
    // },
    {
      name: "Run Options",
      path: `${appLink.editorConfigureRun({
        team: team,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Workspaces",
      path: `${appLink.editorConfigureWorkspaces({
        team: team,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Tokens",
      path: `${appLink.editorConfigureTokens({
        team: team,
        workflow: workflowRef,
      })}`,
    },
  ];

  return (
    <SideNav className={styles.container} border="right">
      <SideNavLinks>
        {navigationItems.map((item) => {
          return <SideNavLink to={item.path}>{item.name}</SideNavLink>;
        })}
      </SideNavLinks>
    </SideNav>
  );
};

export default NavPanel;
