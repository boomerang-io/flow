import Settings, { action } from "Features/WorkspaceDetailed/Settings/Settings";

// Settings tab of /:workspace/manage. No loader: everything shown here (display name, unique
// name, labels) is on the workspace record the parent layout route loads. Renaming, label
// add/remove and workspace delete all post to this route's single intent-keyed action.
// ssr:true means the action runs server-side - see app/routes/globalParameters.tsx.
export { action };

export default function ManageWorkspaceSettingsRoute() {
  return <Settings />;
}
