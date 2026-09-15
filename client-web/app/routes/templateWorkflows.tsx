import TemplateWorkflows, { loader } from "Features/TemplateWorkflows/TemplateWorkflows";
import { Protected } from "Features/App/AppRoutes";

export { loader };

export default function TemplateWorkflowsRoute() {
  return (
    <Protected permission="canReadWorkflowTemplates">
      <TemplateWorkflows />
    </Protected>
  );
}
