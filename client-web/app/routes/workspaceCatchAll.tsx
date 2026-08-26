import { Error404 } from "@boomerang-io/carbon-addons-boomerang-react";
import { WorkspaceContainer } from "Features/App/App";

export default function WorkspaceCatchAllRoute() {
  return (
    <WorkspaceContainer>
      <Error404 theme="boomerang" />
    </WorkspaceContainer>
  );
}
