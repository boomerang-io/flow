import { StrictMode, startTransition } from "react";
import { hydrateRoot } from "react-dom/client";
import { HydratedRouter } from "react-router/dom";
import type { Request as MirageRequest } from "miragejs";

// Cypress test runs stub the backend via miragejs, proxying every request through
// `window.handleFromCypress` (installed by cypress/support/e2e.ts, outside this project's
// tsconfig, before the app loads). Carried over unchanged from the previous src/index.tsx
// entry point - only the final render call below changes, to framework mode's hydrateRoot.
declare global {
  interface Window {
    Cypress?: unknown;
    handleFromCypress?: (
      request: MirageRequest,
    ) => Promise<[number, Record<string, string> | undefined, string | Record<string, unknown>]>;
  }
}

async function bootstrap() {
  if (window.Cypress && window.handleFromCypress) {
    const handleFromCypress = window.handleFromCypress;
    const { Server, Response } = await import("miragejs");
    new Server({
      environment: "test",
      routes() {
        const handler = async (_schema: unknown, request: MirageRequest) => {
          const [status, headers, body] = await handleFromCypress(request);
          return new Response(status, headers, body);
        };
        this.get("/*", handler);
        this.put("/*", handler);
        this.patch("/*", handler);
        this.post("/*", handler);
        this.delete("/*", handler);
      },
    });
  }

  startTransition(() => {
    hydrateRoot(
      document,
      <StrictMode>
        <HydratedRouter />
      </StrictMode>,
    );
  });
}

bootstrap();
