import Settings, { action, loader } from "Features/Settings/Settings";
import { Protected } from "Features/App/AppRoutes";

export { loader, action };

export default function SettingsRoute() {
  return (
    <Protected permission="canReadSettings">
      <Settings />
    </Protected>
  );
}
