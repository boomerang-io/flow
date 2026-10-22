import UserList, { loader } from "Features/Users/Users";
import { Protected } from "Features/App/AppRoutes";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now.
export { loader };

export default function UserListRoute() {
  return (
    <Protected permission="canReadUsers">
      <UserList />
    </Protected>
  );
}
