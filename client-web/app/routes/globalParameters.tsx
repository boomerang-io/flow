import GlobalParameters, { action, loader } from "Features/Parameters/GlobalParameters/GlobalParameters";
import { Protected } from "Features/App/AppRoutes";

// SPA mode (ssr: false) only runs loaders/actions in the browser, so it requires the
// `clientLoader`/`clientAction` export names - `loader`/`action` are reserved for a server
// runtime this app doesn't have and are rejected at build time on non-root routes. The
// component file itself is untouched: it still exports plain `loader`/`action` (consumed
// identically by useLoaderData/useFetcher either way), renamed only at this route-registration
// boundary.
export const clientLoader = loader;
export const clientAction = action;

export default function GlobalParametersRoute() {
  return (
    <Protected permission="canReadParameters">
      <GlobalParameters />
    </Protected>
  );
}
