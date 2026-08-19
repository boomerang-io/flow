import Settings from "Features/Settings";
import { Protected } from "Features/App/AppRoutes";

export default function SettingsRoute() {
  return (
    <Protected permission="canReadSettings">
      <Settings />
    </Protected>
  );
}
