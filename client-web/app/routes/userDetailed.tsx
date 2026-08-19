import UserDetailed, { loader } from "Features/UserDetailed/UserDetailed";
import { Protected } from "Features/App/AppRoutes";

// See app/routes/globalParameters.tsx - SPA mode requires `clientLoader`, not `loader`.
export const clientLoader = loader;

export default function UserDetailedRoute() {
  return (
    <Protected permission="canReadUsers">
      <UserDetailed />
    </Protected>
  );
}
