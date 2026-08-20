declare module "@carbon/pictograms-react";
declare module "@boomerang-io/utils";

// A bare `declare module "react-lazylog";` (no body) types the whole module as `any`, which is
// normally harmless for plain JSX usage but breaks `React.lazy(() => import("react-lazylog")...)`
// (see TaskRunLog.tsx, deferred to a client-only import because this package touches `self` at
// module scope - unrenderable in Node/SSR) - `React.lazy`'s generic can't infer a real component
// type from an `any`-typed module export, so it collapses to a prop-less type. Typed narrowly to
// just the props this app actually passes, matching the upstream (untyped) package's real API.
declare module "react-lazylog" {
  import type { ComponentType, ReactNode } from "react";

  export interface LazyLogProps {
    enableSearch?: boolean;
    fetchOptions?: RequestInit;
    follow?: boolean;
    onScroll?: () => void;
    onError?: (error: boolean) => void;
    selectableLines?: boolean;
    stream?: boolean;
    url: string;
  }

  export const LazyLog: ComponentType<LazyLogProps>;

  export interface ScrollFollowProps {
    startFollowing?: boolean;
    render: (args: { onScroll: () => void }) => ReactNode;
  }

  export const ScrollFollow: ComponentType<ScrollFollowProps>;
}

declare module "react/jsx-runtime";

declare module "*.scss" {
  const styles: { [className: string]: string };
  export default styles;
}

declare module "react/jsx-runtime";
