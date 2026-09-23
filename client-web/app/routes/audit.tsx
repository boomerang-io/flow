import { Protected } from "Features/App/AppRoutes";
import Audit, { loader } from "Features/Audit/Audit";

// See app/routes/userList.tsx - ssr:true means the loader runs server-side.
export { loader };

export default function AuditRoute() {
  return (
    <Protected permission="canReadAudit">
      <Audit />
    </Protected>
  );
}
