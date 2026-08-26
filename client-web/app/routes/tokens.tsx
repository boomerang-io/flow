import Tokens, { action, loader } from "Features/GlobalTokens/GlobalTokens";
import { Protected } from "Features/App/AppRoutes";

// ssr:true means loader/action run server-side in Node - see app/routes/globalParameters.tsx
// for the fuller rationale comment this file follows.
export { loader, action };

export default function TokensRoute() {
  return (
    <Protected permission="canReadTokens">
      <Tokens />
    </Protected>
  );
}
