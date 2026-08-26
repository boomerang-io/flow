import TemplateWorkflows, { action, loader } from "Features/TemplateWorkflows/TemplateWorkflows";
import { Protected } from "Features/App/AppRoutes";

export { loader, action };

export default function TemplateWorkflowsRoute() {
  return (
    <Protected permission="canReadWorkflowTemplates">
      <TemplateWorkflows />
    </Protected>
  );
}
