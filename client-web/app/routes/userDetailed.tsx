import UserDetailed, { loader } from "Features/UserDetailed/UserDetailed";
import { Protected } from "Features/App/AppRoutes";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now.
export { loader };

export default function UserDetailedRoute() {
  return (
    <Protected permission="canReadUsers">
      <UserDetailed />
    </Protected>
  );
}
