import UserList from "Features/Users";
import { Protected } from "Features/App/AppRoutes";

export default function UserListRoute() {
  return (
    <Protected permission="canReadUsers">
      <UserList />
    </Protected>
  );
}
