import GlobalParameters, { action, loader } from "Features/Parameters/GlobalParameters/GlobalParameters";
import { Protected } from "Features/App/AppRoutes";

// ssr:true (react-router.config.ts) means `loader`/`action` now run server-side in Node, which
// is the default this app targets - see CLAUDE.md's client-web SSR direction. Re-exported
// directly (not renamed to clientLoader/clientAction, as under the previous ssr:false/SPA-mode
// build) so react-router treats them as real server route module exports.
export { loader, action };

export default function GlobalParametersRoute() {
  return (
    <Protected permission="canReadParameters">
      <GlobalParameters />
    </Protected>
  );
}
