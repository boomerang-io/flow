import { ManageIndex } from "Features/Manage";

// "/admin" itself: send the caller to the first tab their grants allow.
export default function AdminIndexRoute() {
  return <ManageIndex />;
}
