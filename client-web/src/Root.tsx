import { QueryClient, QueryClientProvider } from "react-query";
import { ReactQueryDevtools } from "react-query/devtools";
import { QueryClient as TanstackQueryClient, QueryClientProvider as TanstackQueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from "react-router-dom";
import "codemirror/addon/fold/foldgutter.css";
import "codemirror/lib/codemirror.css";
import "codemirror/theme/material.css";
import App from "Features/App";
import { appRouteChildren } from "Features/App/AppRoutes";
import ErrorBoundary from "Components/ErrorBoundary";
import { APP_ROOT, isDevEnv, isTestEnv } from "Config/appConfig";

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

// Data-router API (createBrowserRouter/RouterProvider). App is the layout route (chrome:
// navbar, providers, activation/loading/error gates) and renders an <Outlet /> for whichever
// child route matches - the child route tree itself lives in AppRoutes.tsx so that individual
// routes can carry a `loader`/`action`, which only works for routes declared in the router
// config (not for routes matched by a nested <Routes> rendered from inside a component).
const router = createBrowserRouter(
  createRoutesFromElements(
    <Route path="/" element={<App />}>
      {appRouteChildren}
    </Route>,
  ),
  { basename: APP_ROOT },
);

function Root() {
  return (
    <TanstackQueryClientProvider client={tanstackQueryClient}>
      <QueryClientProvider client={queryClient}>
        <ErrorBoundary>
          {isDevEnv && <ReactQueryDevtools initialIsOpen={false} />}
          <RouterProvider router={router} />
        </ErrorBoundary>
      </QueryClientProvider>
    </TanstackQueryClientProvider>
  );
}

export default Root;
