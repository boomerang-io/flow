import { ManageIndex } from "Features/Manage";
import { loader } from "Features/Manage/manageIndexRoute";

// "/admin" itself: the loader sends the caller to the first tab their grants allow before the
// page renders (ssr:true); the element only shows when no tab is allowed or the reads failed.
export { loader };

export default function AdminIndexRoute() {
  return <ManageIndex />;
}
