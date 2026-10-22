import { Error404 } from "@boomerang-io/carbon-addons-boomerang-react";

// Sits under routes/workspaceLayout.tsx like every workspace-scoped route: a KNOWN workspace
// with an unmatched sub-path renders this 404 inside the workspace context; an UNKNOWN
// workspace slug never reaches here - the layout's loader resolves notFound and renders the
// same <Error404> itself.
export default function WorkspaceCatchAllRoute() {
  return <Error404 theme="boomerang" />;
}
