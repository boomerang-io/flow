import Tokens from "Features/GlobalTokens/GlobalTokens";
import { Protected } from "Features/App/AppRoutes";

export default function TokensRoute() {
  return (
    <Protected permission="canReadTokens">
      <Tokens />
    </Protected>
  );
}
