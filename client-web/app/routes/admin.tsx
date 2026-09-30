import Manage from "Features/Manage";

// Layout route for the Manage area (path "/admin"): the header, the breadcrumb and the tab row.
// The nested tab routes are declared in app/routes.ts, each with its own loader/action, and the
// route permissions computed by the App layout pass through this layout's <Outlet context>.
export default function AdminRoute() {
  return <Manage />;
}
