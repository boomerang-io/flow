// A tiny in-memory store the MSW handlers mutate (create/update/delete), seeded from the same
// fixtures the Mirage server (src/ApiServer/fixtures) already uses. Kept intentionally simple -
// this is a test double, not a relational engine - handlers look records up by id/name within a
// single collection; there's no cross-collection referential integrity.
//
// `structuredClone` gives every test its own copy of the fixture data (fixtures are plain
// JSON-shaped modules - dates as ISO strings, no functions/class instances - so it's a safe deep
// clone here), so mutations in one test can never leak into another.
//
// Each collection is typed as a light interface covering only the fields the handlers actually
// read or write, `extends Record<string, unknown>` for the rest of each fixture record's shape.
// A tighter type (matching every field TS infers from the fixture literal) would fight every
// handler that creates or merges a partial record - the point here is a permissive test double,
// not a schema.
import * as fixtures from "ApiServer/fixtures";

// Every field below is optional: some fixture records are unions of differently-shaped objects
// (e.g. approval vs manual-task actions), so a field required here would reject the variants that
// happen not to carry it - and a created/merged record built from a request body TS can't see
// inside of (Record<string, unknown>) can't statically prove it carries a given field either.
// Handlers compare these with `===`, which is fine against `string | undefined`.
interface Workspace extends Record<string, unknown> {
  id?: string;
  name?: string;
  parameters?: Array<Record<string, unknown>>;
  labels?: Record<string, unknown>;
}

interface NamedRecord extends Record<string, unknown> {
  id?: string;
  name?: string;
}

interface GlobalParam extends Record<string, unknown> {
  name?: string;
}

interface User extends Record<string, unknown> {
  id?: string;
  name?: string;
  email?: string;
}

// A generic identity clone, called with an explicit type argument below (e.g. `clone<Workspace>
// (...)`) so each collection ends up typed as the light interface it needs rather than the exact
// literal type TS infers from the fixture module - the type argument makes this a normal
// (checked) call, not a cast: TS still verifies the fixture data structurally satisfies the
// target interface at the call site.
function clone<T>(value: T[]): T[] {
  return structuredClone(value);
}

export function createDb() {
  return {
    workspaces: clone<Workspace>(fixtures.workspaces.content),
    globalParams: clone<GlobalParam>(fixtures.globalParams),
    users: clone<User>(fixtures.users.content),
    settings: clone<Record<string, unknown>>(fixtures.settings),
    tokens: clone<NamedRecord>(fixtures.tokens.content),
    approverGroups: clone<NamedRecord>(fixtures.approverGroups),
    workflows: clone<NamedRecord>(fixtures.workflows.content),
    tasks: clone<NamedRecord>(fixtures.task.content),
    schedules: clone<{ id?: string } & Record<string, unknown>>(fixtures.workflowSchedules.content),
  };
}

export type Db = ReturnType<typeof createDb>;

export const db: Db = createDb();

// Handlers import `db` by reference (the object itself is reassigned here, not recreated), so
// calling this between tests resets every collection back to its fixture-seeded state without
// consumers needing to re-import anything.
export function resetDb(): void {
  Object.assign(db, createDb());
}
