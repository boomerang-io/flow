import { useSyncExternalStore, type ReactNode } from "react";

const emptySubscribe = () => () => {};

/**
 * Renders `children` only once the component has mounted in the browser - never during SSR, and
 * never during the initial client render that hydrates against the server-rendered markup (both
 * of those passes must produce identical output, so both get `fallback`; the live subtree swaps
 * in on the next client render, safely past hydration). This is the standard "useHydrated"
 * pattern (`useSyncExternalStore` with a snapshot that differs between server and client).
 *
 * `children` is a thunk, not a plain node: some third-party libraries this app depends on read
 * `window`/`document`/`navigator` at *module scope* (CodeMirror 5 is the sharpest example - see
 * TaskTemplateEditor.tsx and TextEditorModal.tsx), so importing them eagerly crashes Node
 * regardless of whether the resulting component ever renders. Pair this with `React.lazy` so the
 * *import itself* is deferred - `ClientOnly` gates whether the lazy component's dynamic import
 * ever fires, not just what the component reads once mounted. Merely guarding property access
 * inside the component does not help here, because the crash happens before that code runs.
 */
export default function ClientOnly({
  children,
  fallback = null,
}: {
  children: () => ReactNode;
  fallback?: ReactNode;
}) {
  const isClient = useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false,
  );
  return <>{isClient ? children() : fallback}</>;
}
