import React from "react";
import {
  FeatureSideNav as SideNav,
  FeatureSideNavLink as SideNavLink,
  FeatureSideNavLinks as SideNavLinks,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import styles from "./navPanel.module.scss";

interface NavPanelProps {
  workspace: string;
  workflowRef: string;
}

const NavPanel: React.FC<NavPanelProps> = ({ workspace, workflowRef }) => {
  // List of Nav Items
  const navigationItems = [
    {
      name: "General",
      path: `${appLink.editorConfigureGeneral({
        workspace: workspace,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Triggers",
      path: `${appLink.editorConfigureTriggers({
        workspace: workspace,
        workflow: workflowRef,
      })}`,
    },
    // {
    //   name: "Parameters",
    //   path: `${appLink.editorConfigureParams({
    //     workspace: workspace,
    //     workflow: workflowRef,
    //   })}`,
    // },
    {
      name: "Run Options",
      path: `${appLink.editorConfigureRun({
        workspace: workspace,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Workspaces",
      path: `${appLink.editorConfigureWorkspaces({
        workspace: workspace,
        workflow: workflowRef,
      })}`,
    },
    {
      name: "Tokens",
      path: `${appLink.editorConfigureTokens({
        workspace: workspace,
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
