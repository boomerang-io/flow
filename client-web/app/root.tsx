import type { ReactNode } from "react";
import { Links, Meta, Outlet, ScrollRestoration, Scripts, useHref, useRouteLoaderData } from "react-router";
import type { LoaderFunctionArgs } from "react-router";
import {
  QueryClient as TanstackQueryClient,
  QueryClientProvider as TanstackQueryClientProvider,
} from "@tanstack/react-query";
import "codemirror/addon/fold/foldgutter.css";
import "codemirror/lib/codemirror.css";
import "codemirror/theme/material.css";
import "Styles/styles.scss";
import ErrorBoundary from "Components/ErrorBoundary";
import { isDevEnv, isTestEnv } from "Config/appConfig";
import { loader as bootstrapLoader } from "Features/App/App";
import { readServerData, type ServerData } from "./serverData.server";

// The bootstrap loader (profile/context/feature-flags/navigation/workflow-templates - see
// Features/App/App.tsx for the full rationale) is implemented next to the component that
// consumes it, same as every other route module in this app (e.g. app/routes/globalParameters.tsx
// re-exports its loader from Features/Parameters/GlobalParameters/GlobalParameters.tsx). It runs
// here because this file - the framework-mode root route, id "root" - is what every route in
// app/routes.ts is nested under, so its loader is the one guaranteed to run before any of them
// render. The root loader adds the deployment's runtime values for the browser (serverData, see
// Layout below); the bootstrap never throws, so Layout can always read them.
export { shouldRevalidate } from "Features/App/App";

export async function loader(args: LoaderFunctionArgs) {
  const bootstrap = await bootstrapLoader(args);
  return { ...bootstrap, serverData: readServerData() };
}

// @tanstack/react-query v5 - required by the design-system wrapper's UIShell/Header ONLY.
// The app's own data fetching runs entirely through route loaders/actions and the /res/*
// resource routes (react-query v3 was removed with the last useQuery call site).
const tanstackQueryClient = new TanstackQueryClient({
  defaultOptions: {
    queries: {
      retry: isDevEnv || isTestEnv ? 0 : 3,
      refetchOnWindowFocus: false,
    },
  },
});

// JSON inside an inline <script>: "<" is escaped so no value can close the script element.
function serverDataScript(serverData: ServerData): string {
  return `window._SERVER_DATA = ${JSON.stringify(serverData).replace(/</g, "\\u003c")};`;
}

// The HTML document shell - replaces the previous static index.html. Framework mode renders it
// on the server for every document request (react-router.config.ts: ssr: true). The markup -
// meta tags, favicon, banner comment, `window.global` shim, and the `#app` div - is carried over
// from the old index.html: `#app` in particular is load-bearing (see Styles/_base.scss's `#app`
// rules and react-modal's `Modal.setAppElement("#app")` expectation), so it stays a real element
// in the tree.
//
// `window._SERVER_DATA` is written here, ahead of the module scripts, because Config/appConfig.ts
// and Config/servicesConfig.ts read it as they load. useRouteLoaderData rather than
// useLoaderData: Layout also wraps the root error boundary, where the loader data can be absent,
// and then the browser bundle's own defaults apply.
export function Layout({ children }: { children: ReactNode }) {
  const rootData = useRouteLoaderData("root") as { serverData?: ServerData } | undefined;
  const favicon = useHref("/favicon.ico");
  return (
    <html lang="en" data-carbon-theme="boomerang">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no" />
        <meta name="theme-color" content="#000000" />
        <link rel="shortcut icon" href={favicon} />
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
        <script dangerouslySetInnerHTML={{ __html: serverDataScript(rootData?.serverData ?? {}) }} />
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

// Root layout route. Framework mode owns route resolution/code-splitting itself (each `route()`
// entry in routes.ts is its own lazily-loaded chunk), so the previous manual
// `createBrowserRouter`/`RouterProvider` wiring from src/Root.tsx is gone - `<Outlet />` is
// where the matched route (ultimately Features/App via routes.ts's layout() wrap) renders.
// Everything else here - the design-system query client provider, ErrorBoundary - is carried
// over from src/Root.tsx.
export default function AppRoot() {
  return (
    <TanstackQueryClientProvider client={tanstackQueryClient}>
      <ErrorBoundary>
        <Outlet />
      </ErrorBoundary>
    </TanstackQueryClientProvider>
  );
}
