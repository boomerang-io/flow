import TemplateWorkflows from "Features/TemplateWorkflows";
import { Protected } from "Features/App/AppRoutes";

export default function TemplateWorkflowsRoute() {
  return (
    <Protected permission="canReadWorkflowTemplates">
      <TemplateWorkflows />
    </Protected>
  );
}
