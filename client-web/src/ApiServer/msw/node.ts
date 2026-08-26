// Node-side MSW server. This is the piece Mirage cannot provide: Mirage patches the global XHR
// object, which only exists in a browser/jsdom document - it cannot intercept the `fetch` calls
// SSR route loaders make directly in the Node process (see react-router.config.ts /
// app/entry.server.tsx for where those loaders run). MSW's Node server intercepts at the
// network-module level instead, so the same `handlers` array works for both environments.
//
// Not wired into any loader or test setup yet - see the module doc in ./handlers.ts.
import { setupServer } from "msw/node";
import { handlers } from "./handlers";

export const server = setupServer(...handlers);
