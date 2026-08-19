import React from "react";
import { useFeature } from "flagged";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { FeatureFlag } from "Config/appConfig";
import { CORE_ENV_URL } from "Config/appConfig";

type NoWorkspacesRedirectPromptProps = {
  className?: string; 
  style?: object;
}

const NoWorkspacesRedirectPrompt = ({ className, style }: NoWorkspacesRedirectPromptProps) => {
  const WorkspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);

  const title = WorkspaceManagementEnabled ? "Welcome to Boomerang Flow" : "Crikey, how did you get here?!";
  const message = WorkspaceManagementEnabled ? (
    <p>
      You’re not a member of any workspaces yet. Before you can do much in this wonderful tool, please have an admin add you
      to a workspace.{" "}
    </p>
  ) : (
    <p>
      You’re not a member of any workspaces with access to Boomerang Flow.{" "}
      <a href={`${CORE_ENV_URL}/launchpad`}>Head over to Launchpad</a> to join or create a workspace authorized for Flow.
    </p>
  );

  return (
    <div className={className} style={style}>
      <Error403 header={null} title={title} message={message} theme="boomerang"/>
    </div>
  );
};

export default NoWorkspacesRedirectPrompt;
