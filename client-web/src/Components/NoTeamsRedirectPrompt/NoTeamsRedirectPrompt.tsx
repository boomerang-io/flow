import React from "react";
import { useFeature } from "flagged";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { FeatureFlag } from "Config/appConfig";
import { CORE_ENV_URL } from "Config/appConfig";

type NoTeamsRedirectPromptProps = {
  className?: string; 
  style?: object;
}

const NoTeamsRedirectPrompt = ({ className, style }: NoTeamsRedirectPromptProps) => {
  const TeamManagementEnabled = useFeature(FeatureFlag.TeamManagementEnabled);

  const title = TeamManagementEnabled ? "Welcome to Boomerang Flow" : "Crikey, how did you get here?!";
  const message = TeamManagementEnabled ? (
    <p>
      You’re not a member of any teams yet. Before you can do much in this wonderful tool, please have an admin add you
      to a team.{" "}
    </p>
  ) : (
    <p>
      You’re not a member of any teams with access to Boomerang Flow.{" "}
      <a href={`${CORE_ENV_URL}/launchpad`}>Head over to Launchpad</a> to join or create a team authorized for Flow.
    </p>
  );

  return (
    <div className={className} style={style}>
      <Error403 header={null} title={title} message={message} theme="boomerang"/>
    </div>
  );
};

export default NoTeamsRedirectPrompt;
