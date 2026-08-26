import type { ReactNode } from "react";
import { Links, Meta, Outlet, ScrollRestoration, Scripts } from "react-router";
import { QueryClient, QueryClientProvider } from "react-query";
import { ReactQueryDevtools } from "react-query/devtools";
import {
  QueryClient as TanstackQueryClient,
  QueryClientProvider as TanstackQueryClientProvider,
} from "@tanstack/react-query";
import "codemirror/addon/fold/foldgutter.css";
import "codemirror/lib/codemirror.css";
import "codemirror/theme/material.css";
import "Config/axiosGlobalConfig";
import "Styles/styles.scss";
import ErrorBoundary from "Components/ErrorBoundary";
import { isDevEnv, isTestEnv } from "Config/appConfig";

// The bootstrap loader (profile/context/feature-flags/navigation/workflow-templates - see
// Features/App/App.tsx for the full rationale) is implemented next to the component that
// consumes it, same as every other route module in this app (e.g. app/routes/globalParameters.tsx
// re-exports its loader from Features/Parameters/GlobalParameters/GlobalParameters.tsx). It's
// re-exported from here because this file - the framework-mode root route, id "root" - is what
// every route in app/routes.ts is nested under, so its loader is the one guaranteed to run before
// any of them render.
export { loader, shouldRevalidate } from "Features/App/App";

// react-query v3 - all existing app data fetching (useQuery/useMutation call sites).
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: isDevEnv || isTestEnv ? 0 : 3,
      refetchOnWindowFocus: false,
    },
  },
});

// @tanstack/react-query v5 - required by the design-system wrapper's UIShell/Header.
// The app's own data fetching stays on react-query v3 above; the two are separate
// packages that coexist without conflict.
const tanstackQueryClient = new TanstackQueryClient({
  defaultOptions: {
    queries: {
      retry: isDevEnv || isTestEnv ? 0 : 3,
      refetchOnWindowFocus: false,
    },
  },
});

// The HTML document shell - replaces the previous static index.html. Framework mode renders
// this (and only this, in SPA mode - see react-router.config.ts) on the Node side at build time
// to produce server/build/index.html; every other route only ever renders client-side. The
// markup - meta tags, favicon, banner comment, `window.global` shim, and the `#app` div - is
// carried over unchanged from the old index.html: `#app` in particular is load-bearing (see
// Styles/_base.scss's `#app` rules and react-modal's `Modal.setAppElement("#app")` expectation),
// so it stays a real element in the tree rather than the empty pre-existing DOM node it used to
// be an implicit `createRoot` target for.
export function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" data-carbon-theme="boomerang">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no" />
        <meta name="theme-color" content="#000000" />
        <link rel="shortcut icon" href="/favicon.ico" />
        <title>Boomerang Flow</title>
        {/*
            ____
          / __ )____  ____  ____ ___  ___  _________ _____  ____ _
          / __  / __ \/ __ \/ __ `__ \/ _ \/ ___/ __ `/ __ \/ __ `/
        / /_/ / /_/ / /_/ / / / / / /  __/ /  / /_/ / / / / /_/ /
        /_____/\____/\____/_/ /_/ /_/\___/_/   \__,_/_/ /_/\__, /
                                                          /____/
        */}
        <script dangerouslySetInnerHTML={{ __html: "window.global = window" }} />
        <Meta />
        <Links />
      </head>
      <body>
        <noscript>Whoops. You need to enable JavaScript to run this app.</noscript>
        <div id="app">{children}</div>
        <div hidden>
          <span id="new-window-aria-desc-0">Opens in a new tab</span>
        </div>
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

// Root layout route. Framework mode owns route resolution/code-splitting itself now (each
// `route()` entry in routes.ts is its own lazily-loaded chunk), so the previous manual
// `createBrowserRouter`/`RouterProvider` wiring from src/Root.tsx is gone - `<Outlet />` is
// where the matched route (ultimately Features/App via routes.ts's layout() wrap) renders.
// Everything else here - the two query client providers, the devtools gate, ErrorBoundary - is
// carried over unchanged from src/Root.tsx.
export default function AppRoot() {
  return (
    <TanstackQueryClientProvider client={tanstackQueryClient}>
      <QueryClientProvider client={queryClient}>
        <ErrorBoundary>
          {isDevEnv && <ReactQueryDevtools initialIsOpen={false} />}
          <Outlet />
        </ErrorBoundary>
      </QueryClientProvider>
    </TanstackQueryClientProvider>
  );
}
