import Artifacts, { action, loader } from "Features/WorkspaceDetailed/Artifacts/Artifacts";

// Artifacts tab of /:workspace/manage. The workspace's own storage summary (quotas) and workflow
// names come from the parent layout route's loader; this route's own loader fetches the
// workspace's paginated artifact list, and its action deletes one artifact by id.
export { action, loader };

export default function ManageWorkspaceArtifactsRoute() {
  return <Artifacts />;
}
