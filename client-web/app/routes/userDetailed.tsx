import UserDetailed, { action, loader } from "Features/UserDetailed/UserDetailed";
import { Protected } from "Features/App/AppRoutes";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now. The `action`
// serves both write sites on the page (Header/ChangeRole and Labels/UserLabels) via an `intent`
// form field; both submit through a bare useFetcher(), which resolves here.
export { loader, action };

export default function UserDetailedRoute() {
  return (
    <Protected permission="canReadUsers">
      <UserDetailed />
    </Protected>
  );
}
