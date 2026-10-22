import type { TestingLibraryMatchers } from "@types/testing-library__jest-dom/matchers";

// The installed @testing-library/jest-dom (v5) does not ship its own type
// definitions or a "/vitest" entrypoint (that subpath was added only in
// jest-dom v6+); the matchers themselves are registered at runtime in
// src/setupTests.tsx via `import "@testing-library/jest-dom/extend-expect"`.
// This augments Vitest's own Assertion types with the matcher shapes from the
// DefinitelyTyped package so the declared types stay in sync with what
// actually runs.
declare module "vitest" {
  interface Assertion<T = any> extends TestingLibraryMatchers<T, void> {}
  interface AsymmetricMatchersContaining extends TestingLibraryMatchers<unknown, void> {}
}
