import Settings from "Features/WorkspaceDetailed/Settings/Settings";

// Settings tab of /:workspace/manage. No loader: everything shown here (display name, unique
// name, labels) is on the workspace record the parent layout route loads.

export default function ManageWorkspaceSettingsRoute() {
  return <Settings />;
}
